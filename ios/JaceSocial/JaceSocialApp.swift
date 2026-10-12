import SwiftUI

@main
struct JaceSocialApp: App {
    @StateObject private var session = Session()

    init() {
        // test builds: -jaceToken <t> signs in with that token (CI screenshots with native-test/mock_api.py)
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "jaceToken") { Keychain.write(t) }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            SwiftUI.Group {
                if session.token == nil { SignInView() } else { MainView(session: session).id(session.token) }
            }
            .environmentObject(session)
            .preferredColorScheme(.dark)
            .tint(JaceColor.green)
        }
    }
}
