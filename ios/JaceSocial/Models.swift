import Foundation

// Mirrors server/lib/client.ts. Missing fields get defaults, unknown ones are ignored.

struct Activity: Codable, Hashable {
    var type: String = ""
    var version: String?
    var loader: String?
    var server: String?
}

struct Link: Codable, Hashable {
    var label: String = ""
    var url: String = ""
}

struct Person: Codable, Hashable, Identifiable {
    var uuid: String
    var name: String = ""
    var online: Bool = false
    var status: String = "offline"
    var activity: Activity?
    var custom_status: String?
    var status_emoji: String?
    var minecraft_name: String?
    var jace_name: String?
    var avatar_url: String?
    var bio: String?
    var pronouns: String?
    var unread: Int?
    var is_bot: Bool?
    var nickname: String?
    var display_name: String?

    var id: String { uuid }
    var shownName: String {
        if let n = nickname, !n.isEmpty { return n }
        if let n = display_name, !n.isEmpty { return n }
        return name
    }
}

struct Realtime: Codable, Hashable {
    var url: String = ""
    var key: String = ""
}

struct Me: Codable {
    var uuid: String
    var name: String = ""
    var display_name: String?
    var avatar_url: String?
    var status: String = "online"
    var inbox: String = ""
    var realtime: Realtime = Realtime()
    var onboarded: Bool?
    var is_bot: Bool?
}

struct FriendsData: Codable {
    var friends: [Person] = []
    var incoming: [Person] = []
    var outgoing: [Person] = []
}

/// What signing in gives: the session token and where live events come from.
struct SignedIn: Codable {
    var token: String
    var uuid: String = ""
    var name: String = ""
    var inbox: String = ""
}

struct JaceStart: Codable {
    var url: String
    var state: String
    var poll_key: String
}

struct DeviceCode: Codable {
    var user_code: String
    var verification_uri: String
    var interval: Int = 5
    var ticket: String
}
