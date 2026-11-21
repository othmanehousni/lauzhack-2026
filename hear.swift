// Live speech to text with Apple's on-device SpeechTranscriber (macOS 26), for ear.py, which builds and starts it.
//
// Reads names to listen for (apps, websites, slide titles) on stdin, one per line, up to an empty line. Then prints one
// JSON line each time the words of the utterance being spoken change: {"text", "kind": "live" | "final", "ago"}, where
// "ago" is how many seconds ago the last voiced sound was. A pause of PAUSE seconds asks the analyzer to finalize what
// was said, and the final text of the utterance follows as soon as it is ready.
import AVFoundation
import Speech

let PAUSE = 0.6  // seconds of quiet that end an utterance
let WAIT = 0.8  // longest wait for the final text after a pause, before the latest tentative text stands for it

func emit(_ fields: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: fields)
    FileHandle.standardOutput.write(data + Data("\n".utf8))
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("hear: \(message)\n".utf8))
    exit(1)
}

/// How long the microphone has run and when it last heard a voice, in seconds of audio, written on the audio thread.
final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var now = 0.0, voice = 0.0, floor = 0.003

    func add(_ buffer: AVAudioPCMBuffer) {
        let n = Int(buffer.frameLength)
        guard n > 0, let samples = buffer.floatChannelData?[0] else { return }
        var sum: Float = 0
        for i in 0..<n { sum += samples[i] * samples[i] }
        let rms = Double((sum / Float(n)).squareRoot())
        lock.lock()
        defer { lock.unlock() }
        now += Double(n) / buffer.format.sampleRate
        if rms > max(3 * floor, 0.01) {
            voice = now
        } else {
            floor = 0.995 * floor + 0.005 * rms  // the room's noise, learned while nobody speaks
        }
    }

    var read: (now: Double, voice: Double) {
        lock.lock()
        defer { lock.unlock() }
        return (now, voice)
    }
}

@MainActor
final class Utterance {
    var said = ""  // final text of the utterance so far
    var tentative = ""  // the latest volatile text after it
    var ending: Double? = nil  // audio time through which a pause asked for final text
    var asked = 0.0  // when, in audio time

    func text() -> String { [said, tentative].filter { !$0.isEmpty }.joined(separator: " ") }

    func send(_ kind: String, _ clock: Clock) {
        let t = clock.read
        emit(["text": text(), "kind": kind, "ago": max(0, t.now - t.voice)])
    }

    func end(_ clock: Clock) {
        if !text().isEmpty { send("final", clock) }
        said = ""
        tentative = ""
        ending = nil
    }
}

@main
struct Hear {
    @MainActor
    static func main() async throws {
        var names: [String] = []
        while let line = readLine(), !line.isEmpty { names.append(line) }
        if SFSpeechRecognizer.authorizationStatus() == .notDetermined {
            _ = await withCheckedContinuation { done in SFSpeechRecognizer.requestAuthorization { done.resume(returning: $0) } }
        }

        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: "en-US")) else {
            fail("this Mac has no English model for SpeechTranscriber")
        }
        let transcriber = SpeechTranscriber(locale: locale, preset: .progressiveTranscription)
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            emit(["note": "downloading Apple's English speech model (once)"])
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

        let (inputs, feed) = AsyncStream.makeStream(of: AnalyzerInput.self)
        let clock = Clock()
        let engine = AVAudioEngine()
        let mic = engine.inputNode.outputFormat(forBus: 0)
        guard let converter = AVAudioConverter(from: mic, to: format) else { fail("cannot convert \(mic) to \(format)") }
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: mic) { buffer, _ in
            clock.add(buffer)
            let frames = AVAudioFrameCount(Double(buffer.frameLength) * format.sampleRate / mic.sampleRate) + 1
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
            if error == nil, out.frameLength > 0 { feed.yield(AnalyzerInput(buffer: out)) }
        }
        try await analyzer.start(inputSequence: inputs)
        engine.prepare()
        try engine.start()
        emit(["note": "listening... (ctrl-c to stop)"])

        let utterance = Utterance()
        Task { @MainActor in
            while true {
                try await Task.sleep(for: .milliseconds(100))
                let t = clock.read
                if utterance.ending != nil {
                    if t.now - utterance.asked > WAIT { utterance.end(clock) }
                } else if !utterance.tentative.isEmpty, t.now - t.voice >= PAUSE {
                    let through = t.voice + 0.25
                    utterance.ending = through
                    utterance.asked = t.now
                    try? await analyzer.finalize(through: CMTime(seconds: through, preferredTimescale: 48_000))
                }
            }
        }
        for try await result in transcriber.results {
            let text = String(result.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
            if result.isFinal {
                utterance.said = [utterance.said, text].filter { !$0.isEmpty }.joined(separator: " ")
                utterance.tentative = ""
                if let through = utterance.ending, result.range.end.seconds >= through - 0.3 {
                    utterance.end(clock)
                } else if !utterance.said.isEmpty {
                    utterance.send("live", clock)
                }
            } else if text != utterance.tentative {
                utterance.tentative = text
                if !text.isEmpty { utterance.send("live", clock) }
            }
        }
    }
}
