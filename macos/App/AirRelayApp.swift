import AirRelayKit
import Combine
import SwiftUI

@main
struct AirRelayApp: App {
    @StateObject private var engine: SyncEngine
    private let callPanel = CallPanelController()
    private let serviceProvider: FileServiceProvider
    private let cancellable: AnyCancellable

    init() {
        let engine = SyncEngine()
        engine.start()
        _engine = StateObject(wrappedValue: engine)
        let panel = callPanel
        cancellable = engine.$activeCall
            .receive(on: DispatchQueue.main)
            .sink { [weak engine] call in
                guard let engine else { return }
                panel.update(call: call, engine: engine)
            }
        serviceProvider = FileServiceProvider(engine: engine)
        NSApplication.shared.servicesProvider = serviceProvider
        NSUpdateDynamicServices()
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarView()
                .environmentObject(engine)
        } label: {
            Image(
                systemName: engine.isConnected
                    ? "iphone.gen3.radiowaves.left.and.right" : "iphone.gen3.slash"
            )
            .dropDestination(for: URL.self) { urls, _ in
                guard engine.isConnected else { return false }
                for url in urls where url.isFileURL {
                    engine.fileTransfer.offer(fileURL: url)
                }
                return true
            }
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView()
                .environmentObject(engine)
        }

        Window("Air Relay Help", id: "help") {
            HelpView()
        }
        .windowResizability(.contentSize)
    }
}
