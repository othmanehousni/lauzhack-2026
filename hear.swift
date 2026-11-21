// Live speech to text with Apple's on-device SpeechTranscriber (macOS 26), for ear.py, which builds and starts it.
//
// Reads names to listen for (apps, websites, slide titles) on stdin, one per line, up to an empty line. Then prints one
// JSON line each time the words of the utterance being spoken change: {"text", "kind": "live" | "final", "ago"}, where
// "ago" is how many seconds ago the words last changed. When they have not changed for PAUSE seconds, the presenter has
// paused: the analyzer finalizes what it heard and the final text of the utterance follows.
//
// With audio files as arguments, it hears those instead of the microphone, at the pace they would be spoken, each
// followed by GAP seconds of silence, and prints {"note": "playing N"} as file N starts.
import AVFoundation
import Speech

let PAUSE = 1.0  // seconds without new words that end an utterance; clear requests act on the live words before
let GAP = 2.0  // silence after each audio file

func emit(_ fields: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: fields)
    FileHandle.standardOutput.write(data + Data("\n".utf8))
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("hear: \(message)\n".utf8))
    exit(1)
}

/// Audio of any format, converted to the analyzer's and passed on, with a count of the seconds heard.
final class Feed: @unchecked Sendable {
    let format: AVAudioFormat, inputs: AsyncStream<AnalyzerInput>.Continuation
    private let lock = NSLock()
    private var heard = 0.0
    private var converter: AVAudioConverter?

    init(_ format: AVAudioFormat, _ inputs: AsyncStream<AnalyzerInput>.Continuation) {
        (self.format, self.inputs) = (format, inputs)
    }

    var now: Double {
        lock.lock()
        defer { lock.unlock() }
        return heard
    }

    func push(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        heard += Double(buffer.frameLength) / buffer.format.sampleRate
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

/// The utterance being spoken: its final text so far, the tentative text after it, and when either last changed.
@MainActor
final class Utterance {
    var said = "", tentative = "", changed = 0.0

    var text: String { [said, tentative].filter { !$0.isEmpty }.joined(separator: " ") }

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
        let transcriber = SpeechTranscriber(locale: locale, preset: .progressiveTranscription)
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
                guard !utterance.text.isEmpty, feed.now - utterance.changed >= PAUSE else { continue }
                if !utterance.tentative.isEmpty {
                    try? await analyzer.finalize(through: nil)  // everything heard so far
                    try await Task.sleep(for: .milliseconds(50))  // for its final results to arrive
                }
                utterance.send("final", feed.now)
                (utterance.said, utterance.tentative) = ("", "")
            }
        }
        for try await result in transcriber.results {
            let text = String(String(result.text.characters).drop { $0.isWhitespace || $0.isPunctuation })
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if result.isFinal {
                utterance.said = [utterance.said, text].filter { !$0.isEmpty }.joined(separator: " ")
                utterance.tentative = ""
            } else if text != utterance.tentative {
                utterance.tentative = text
            } else {
                continue
            }
            utterance.changed = feed.now
            utterance.send("live", feed.now)
        }
    }
}
