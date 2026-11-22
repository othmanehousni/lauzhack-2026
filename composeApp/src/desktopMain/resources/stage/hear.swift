// Live speech to text with Apple's on-device SpeechTranscriber (macOS 26), for Ear.kt, which builds and starts it.
//
// Reads names to listen for (apps, websites, slide titles) on stdin, one per line, up to an empty line. Then prints one
// JSON line each time the words of the utterance being spoken change: {"text", "kind": "live" | "final", "ago"}, where
// "ago" is how many seconds ago the words last changed. When the microphone has been quiet for PAUSE seconds and no new
// words came for SETTLED seconds, the presenter has paused and the utterance's final text follows. Both are needed:
// words arrive up to a second late, and loudness alone cannot say which words. What was said before the voice stopped
// was sent: the analyzer's final results time each word, so the words of one that also covers what is said next are
// split there. (Asked to finalize early, the analyzer writes worse text, so it finalizes when it decides to.)
// Ten times a second it also prints {"level"}, the RMS loudness of the audio since the last one.
//
// With audio files as arguments, it hears those instead of the microphone, at the pace they would be spoken, each
// followed by GAP seconds of silence, and prints {"note": "playing N"} as file N starts.
import AVFoundation
import Speech

let PAUSE = 1.2  // seconds of quiet that end an utterance; clear requests act on the live words before
let SETTLED = 0.6  // seconds without new words, so the last ones have arrived
let TAIL = 1.0  // a word's quiet end can outlast the loud part by this much; what is said next starts PAUSE after
let GAP = 2.0  // silence after each audio file

func emit(_ fields: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: fields)
    FileHandle.standardOutput.write(data + Data("\n".utf8))
}

/// The words of a text, without the punctuation and capitals the analyzer keeps refining after they were said.
func words(_ text: String) -> [Substring] {
    text.lowercased().split { !$0.isLetter && !$0.isNumber }
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("hear: \(message)\n".utf8))
    exit(1)
}

/// Audio of any format, converted to the analyzer's and passed on, with a count of the seconds heard and when it was
/// last loud: above three times the room's noise, learned while it is quiet.
final class Feed: @unchecked Sendable {
    let format: AVAudioFormat, inputs: AsyncStream<AnalyzerInput>.Continuation
    private let lock = NSLock()
    private var heard = 0.0, loud = 0.0, floor = 0.003
    private var energy: Float = 0, frames = 0
    private var converter: AVAudioConverter?

    init(_ format: AVAudioFormat, _ inputs: AsyncStream<AnalyzerInput>.Continuation) {
        (self.format, self.inputs) = (format, inputs)
    }

    var now: Double {
        lock.lock()
        defer { lock.unlock() }
        return heard
    }

    var quiet: Double {
        lock.lock()
        defer { lock.unlock() }
        return heard - loud
    }

    /// The RMS loudness since the last call.
    func level() -> Double {
        lock.lock()
        defer { (energy, frames) = (0, 0); lock.unlock() }
        return frames > 0 ? Double((energy / Float(frames)).squareRoot()) : 0
    }

    func push(_ buffer: AVAudioPCMBuffer) {
        let n = Int(buffer.frameLength)
        var sum: Float = 0
        if let samples = buffer.floatChannelData?[0] { for i in 0..<n { sum += samples[i] * samples[i] } }
        let rms = n > 0 ? Double((sum / Float(n)).squareRoot()) : 0
        lock.lock()
        heard += Double(n) / buffer.format.sampleRate
        (energy, frames) = (energy + sum, frames + n)
        if rms > max(3 * floor, 0.01) { loud = heard } else { floor = 0.995 * floor + 0.005 * rms }
        lock.unlock()
        if converter?.inputFormat != buffer.format { converter = AVAudioConverter(from: buffer.format, to: format) }
        guard let converter else { fail("cannot convert \(buffer.format) to \(format)") }
        let frames = AVAudioFrameCount(Double(buffer.frameLength) * format.sampleRate / buffer.format.sampleRate) + 1
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return }
        var given = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if given {
                status.pointee = .noDataNow
                return nil
            }
            given = true
            status.pointee = .haveData
            return buffer
        }
        if error == nil, out.frameLength > 0 { inputs.yield(AnalyzerInput(buffer: out)) }
    }

    /// Each file at the pace it would be spoken, then silence; stops the program after the last one.
    func play(_ paths: [String]) async throws {
        for (n, path) in paths.enumerated() {
            let file = try AVAudioFile(forReading: URL(fileURLWithPath: path))
            let step = AVAudioFrameCount(file.processingFormat.sampleRate / 10)
            emit(["note": "playing \(n)"])
            var left = file.length + AVAudioFramePosition(GAP * file.processingFormat.sampleRate)
            while left > 0 {
                guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: step) else { return }
                if file.framePosition < file.length {
                    try file.read(into: buffer, frameCount: step)
                } else {
                    buffer.frameLength = step  // silence: a new buffer is zeroed
                }
                push(buffer)
                left -= AVAudioFramePosition(buffer.frameLength)
                try await Task.sleep(for: .milliseconds(100))
            }
        }
        exit(0)
    }
}

/// What was said since the last utterance sent: the words of the analyzer's final results, each with the audio time it
/// ends at (the first word of a result starts where the result does, said or not), its latest tentative text, and when
/// the words last changed.
@MainActor
final class Utterance {
    var final: [(text: String, end: Double)] = [], tentative = ""
    var cut = 0.0  // audio before this was sent already
    var changed = 0.0

    var text: String {
        (final.filter { $0.end > cut }.map(\.text).joined() + tentative).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func add(_ result: SpeechTranscriber.Result) {
        guard result.range.end.seconds > cut + 0.05 else { return }  // about audio already sent
        if result.isFinal {
            var at = result.range.start.seconds
            final += result.text.runs.map { run in
                if let range = run.audioTimeRange { at = range.end.seconds }
                return (String(result.text[run.range].characters), at)
            }
            tentative = ""
        } else if result.range.start.seconds >= cut - TAIL - 0.3 {  // untimed: only when it starts after the voice stopped
            tentative = String(result.text.characters)
        }
    }

    func send(_ kind: String, _ now: Double) {
        if !text.isEmpty { emit(["text": text, "kind": kind, "ago": max(0, now - changed)]) }
    }
}

@main
struct Hear {
    @MainActor
    static func main() async throws {
        var names: [String] = []
        while let line = readLine(), !line.isEmpty { names.append(line) }

        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: "en-US")) else {
            fail("this Mac has no English model for SpeechTranscriber")
        }
        let transcriber = SpeechTranscriber(locale: locale, preset: .timeIndexedProgressiveTranscription)
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            emit(["note": "getting Apple's English speech model ready"])
            try await request.downloadAndInstall()
        }
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        if !names.isEmpty {
            let context = AnalysisContext()
            context.contextualStrings[.general] = names
            try await analyzer.setContext(context)
        }
        guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            fail("no audio format fits SpeechTranscriber")
        }
        try await analyzer.prepareToAnalyze(in: format)

        let (inputs, continuation) = AsyncStream.makeStream(of: AnalyzerInput.self)
        let feed = Feed(format, continuation)
        try await analyzer.start(inputSequence: inputs)
        let files = Array(CommandLine.arguments.dropFirst())
        let engine = AVAudioEngine()
        if files.isEmpty {
            let mic = engine.inputNode.outputFormat(forBus: 0)
            engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: mic) { buffer, _ in feed.push(buffer) }
            engine.prepare()
            try engine.start()
            emit(["note": "listening... (ctrl-c to stop)"])
        } else {
            Task.detached { try await feed.play(files) }
        }

        let utterance = Utterance()
        Task { @MainActor in
            while true {
                try await Task.sleep(for: .milliseconds(100))
                emit(["level": feed.level()])
                guard !utterance.text.isEmpty, feed.quiet >= PAUSE, feed.now - utterance.changed >= SETTLED else { continue }
                utterance.send("final", feed.now)
                (utterance.cut, utterance.final, utterance.tentative) = (feed.now - feed.quiet + TAIL, [], "")
            }
        }
        for try await result in transcriber.results {
            let before = utterance.text
            utterance.add(result)
            guard utterance.text != before else { continue }  // silence also gets results, empty or repeated
            if words(utterance.text) != words(before) { utterance.changed = feed.now }
            utterance.send("live", feed.now)
        }
    }
}
