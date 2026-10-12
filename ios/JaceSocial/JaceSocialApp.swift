import SwiftUI

@main
struct JaceSocialApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        WindowGroup {
            SwiftUI.Group {
                if session.token == nil {
                    SignInView().preferredColorScheme(.dark).tint(JaceColor.green)
                } else {
                    MainView(session: session).id(session.token)
                }
            }
            .environmentObject(session)
        }
    }
}
