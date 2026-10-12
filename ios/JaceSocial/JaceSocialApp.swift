import SwiftUI

@main
struct JaceSocialApp: App {
    @StateObject private var session = Session()

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
