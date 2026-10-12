import SwiftUI

@main
struct JaceSocialApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        WindowGroup {
            Group {
                if session.token == nil { SignInView() } else { HomeView() }
            }
            .environmentObject(session)
            .preferredColorScheme(.dark)
            .tint(JaceColor.green)
        }
    }
}
