// Whether the presenter is in a call, for the mole's MeetingDetector, which builds and starts it.
//
// Prints one JSON line each time the answer changes:
//   {"call":false,"input":[...]}
//   {"call":true,"app":"Zoom","bundle":"us.zoom.xos","title":"Home insurance review","probable":false,"mic":true,"input":[...]}
// An app joins a call when it captures the microphone and plays sound at once (a voice message or dictation only
// captures, a video only plays), and stays in it while it does either: an app that lets go of the microphone when the
// presenter mutes still plays the others. "mic" says whether it captures now. Only call apps (APPS) and browsers (apps
// that open https links) count, never the program that started this one or its other helpers, such as hear. A
// browser's call is "probable": which tab uses the microphone is not known without Screen Recording or Accessibility
// permission. "input" lists every process capturing the microphone.
//
// The title is the calendar event happening now if calendar access is granted (asked the first time a call is seen,
// never with --no-calendar), else "<app> meeting". Core Audio's process objects (macOS 14.2) need no permission, and
// reading them opens no device. Exits when stdin closes, so it ends with the program that started it.
import AppKit
import CoreAudio
import EventKit

let APPS = [
    "us.zoom.xos": "Zoom",
    "com.microsoft.teams2": "Microsoft Teams",
    "com.apple.FaceTime": "FaceTime",
    "com.apple.avconferenced": "FaceTime",  // the daemon that carries the sound of FaceTime and iPhone calls
    "com.tinyspeck.slackmacgap": "Slack",
    "Cisco-Systems.Spark": "Webex",
    "com.webex.meetingmanager": "Webex",
    "com.hnc.Discord": "Discord",
    "net.whatsapp.WhatsApp": "WhatsApp",
    "ru.keepcoder.Telegram": "Telegram",
    "org.whispersystems.signal-desktop": "Signal",
]
let browsers = Set(NSWorkspace.shared.urlsForApplications(toOpen: URL(string: "https://example.com")!).compactMap { Bundle(url: $0)?.bundleIdentifier })
let calendar = CommandLine.arguments.contains("--no-calendar") ? nil : EKEventStore()
let system = AudioObjectID(kAudioObjectSystemObject)

func known(_ bundle: String?) -> Bool { bundle.map { APPS[$0] != nil || browsers.contains($0) } ?? false }

func address(_ selector: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
}

func read<T: BitwiseCopyable>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, _ value: T) -> T {
    var address = address(selector), value = value, size = UInt32(MemoryLayout<T>.size)
    AudioObjectGetPropertyData(object, &address, 0, nil, &size, &value)
    return value
}

func bundle(_ object: AudioObjectID) -> String {
    var address = address(kAudioProcessPropertyBundleID), size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
    var value: Unmanaged<CFString>?
    guard AudioObjectGetPropertyData(object, &address, 0, nil, &size, &value) == noErr, let value else { return "" }
    return value.takeRetainedValue() as String
}

/// Core Audio's clients, or nil when the list changed while being read.
func processes() -> [AudioObjectID]? {
    var address = address(kAudioHardwarePropertyProcessObjectList), size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(system, &address, 0, nil, &size) == noErr else { return nil }
    var objects = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    guard AudioObjectGetPropertyData(system, &address, 0, nil, &size, &objects) == noErr else { return nil }
    return Array(objects.prefix(Int(size) / MemoryLayout<AudioObjectID>.size))
}

func parent(_ pid: pid_t) -> pid_t {
    var info = proc_bsdinfo()
    return proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, Int32(MemoryLayout<proc_bsdinfo>.size)) > 0 ? pid_t(info.pbi_ppid) : 1
}

func name(_ pid: pid_t) -> String {
    var name = [CChar](repeating: 0, count: 256)
    proc_name(pid, &name, 256)
    return String(cString: name)
}

/// The call app or browser a process works for: itself, the nearest of its parents (browser and Electron helpers), or
/// for WebKit's GPU process, which macOS names after its app ("Safari Graphics and Media"), that app. Nil for the
/// program that started this one and anything it started, whatever it is.
func owner(_ pid: pid_t, _ bundle: String) -> String? {
    var chain: [pid_t] = [], p = pid
    while p > 1 {
        chain.append(p)
        p = parent(p)
    }
    if chain.contains(getppid()) { return nil }
    let ids: [String?] = [bundle] + chain.dropFirst().map { NSRunningApplication(processIdentifier: $0)?.bundleIdentifier }
    if let id = ids.first(where: known) { return id }
    guard bundle.hasPrefix("com.apple.WebKit"), let name = NSRunningApplication(processIdentifier: pid)?.localizedName else { return nil }
    return NSWorkspace.shared.runningApplications.first {
        known($0.bundleIdentifier) && !($0.localizedName ?? "").isEmpty && name.contains($0.localizedName!)
    }?.bundleIdentifier
}

var asked = false

/// The calendar event happening now, if calendar access is granted; the first time, asks for it without waiting.
func event() -> String? {
    guard let calendar else { return nil }
    switch EKEventStore.authorizationStatus(for: .event) {
    case .fullAccess:
        let now = Date(), soon = now.addingTimeInterval(600)  // presenters join up to ten minutes early
        return calendar.events(matching: calendar.predicateForEvents(withStart: now.addingTimeInterval(-86400), end: soon, calendars: nil))
            .filter { !$0.isAllDay && $0.startDate < soon && $0.endDate > now && !($0.title ?? "").isEmpty
                && $0.attendees?.first(where: \.isCurrentUser)?.participantStatus != .declined }
            .min { abs($0.startDate.timeIntervalSince(now)) < abs($1.startDate.timeIntervalSince(now)) }?.title
    case .notDetermined where !asked && Bundle.main.object(forInfoDictionaryKey: "NSCalendarsFullAccessUsageDescription") != nil:
        asked = true
        calendar.requestFullAccessToEvents { _, _ in DispatchQueue.main.async { update() } }
        return nil
    default:
        return nil
    }
}

var calls: [String] = []  // the apps in a call, in the order they joined
var said = Data()

func update() {
    guard let objects = processes() else { return }
    var mic = Set<String>(), sound = Set<String>(), input: [String] = []
    for object in objects {
        let capturing = read(object, kAudioProcessPropertyIsRunningInput, UInt32(0)) != 0
        let playing = read(object, kAudioProcessPropertyIsRunningOutput, UInt32(0)) != 0
        guard capturing || playing else { continue }
        let pid = read(object, kAudioProcessPropertyPID, pid_t(0)), id = bundle(object)
        if capturing { input.append(id.isEmpty ? name(pid) : id) }
        guard let app = owner(pid, id) else { continue }
        if capturing { mic.insert(app) }
        if playing { sound.insert(app) }
    }
    calls = calls.filter { mic.contains($0) || sound.contains($0) } + mic.intersection(sound).subtracting(calls).sorted()
    var line: [String: Any] = ["call": false, "input": input.sorted()]
    if let app = calls.first {
        let name = APPS[app] ?? NSRunningApplication.runningApplications(withBundleIdentifier: app).first?.localizedName ?? app
        line.merge(["call": true, "bundle": app, "app": name, "title": event() ?? "\(name) meeting", "probable": APPS[app] == nil,
                    "mic": mic.contains(app)]) { $1 }
    }
    let data = try! JSONSerialization.data(withJSONObject: line, options: [.sortedKeys, .withoutEscapingSlashes])
    if data != said {
        said = data
        FileHandle.standardOutput.write(data + Data("\n".utf8))
    }
}

// Core Audio does not report changes of a process's IsRunningInput or IsRunningOutput (macOS 26.4), only of its
// IsRunning and of the devices it uses, so a change of any property of any process rescans, and so does a timer.
var watched = Set<AudioObjectID>()
func watch() {
    for object in processes() ?? [] where watched.insert(object).inserted {
        var any = AudioObjectPropertyAddress(mSelector: kAudioPropertyWildcardPropertyID, mScope: kAudioObjectPropertyScopeWildcard,
                                             mElement: kAudioObjectPropertyElementWildcard)
        AudioObjectAddPropertyListenerBlock(object, &any, .main) { _, _ in update() }
    }
}
var list = address(kAudioHardwarePropertyProcessObjectList), restarted = address(kAudioHardwarePropertyServiceRestarted)
AudioObjectAddPropertyListenerBlock(system, &list, .main) { _, _ in
    watch()
    update()
}
AudioObjectAddPropertyListenerBlock(system, &restarted, .main) { _, _ in
    FileHandle.standardError.write(Data("meet: Core Audio restarted, its listeners with it\n".utf8))
    exit(1)
}
Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { _ in update() }.tolerance = 1
Thread {
    while readLine() != nil {}
    exit(0)
}.start()
watch()
update()
RunLoop.main.run()
