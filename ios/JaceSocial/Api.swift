import Foundation

/// An error the server explained (its "error" field), or a network problem.
struct ApiError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

/// The Jace Social API (docs/api.md).
final class Api {
    static let base = URL(string: "https://jace-social.vercel.app/api/v1")!
    var token: () -> String?
    private let decoder = JSONDecoder()

    init(token: @escaping () -> String?) { self.token = token }

    /// method + path ("/me") -> the answer's JSON data. Throws ApiError.
    func call(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> Data {
        var req = URLRequest(url: URL(string: Api.base.absoluteString + path)!)
        req.httpMethod = method
        req.timeoutInterval = 30
        req.setValue("JaceSocialiOS/\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] ?? "?")", forHTTPHeaderField: "User-Agent")
        if let t = token() { req.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        if method != "GET" && method != "DELETE" {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body ?? [:])
        }
        let data: Data, response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw ApiError(status: 0, message: "Can't reach Jace Social - check your internet connection")
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw ApiError(status: code, message: msg ?? "Jace Social error (\(code))")
        }
        return data
    }

    func get<T: Decodable>(_ path: String) async throws -> T { try decoder.decode(T.self, from: try await call("GET", path)) }
    func post<T: Decodable>(_ path: String, _ body: [String: Any] = [:]) async throws -> T {
        try decoder.decode(T.self, from: try await call("POST", path, body: body))
    }

    private func pending(_ data: Data) -> Bool {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["pending"] != nil
    }

    // --- the endpoints so far (more as screens are added)
    func me() async throws -> Me { try await get("/me") }
    func friends() async throws -> FriendsData { try await get("/friends") }
    func startJace() async throws -> JaceStart { try await post("/auth/jace") }
    /// nil while the browser part isn't done yet
    func pollJace(_ s: JaceStart) async throws -> SignedIn? {
        let data = try await call("GET", "/auth/jace/poll?state=\(s.state)&key=\(s.poll_key)")
        return pending(data) ? nil : try decoder.decode(SignedIn.self, from: data)
    }
    func startMinecraft() async throws -> DeviceCode { try await post("/auth/minecraft") }
    func pollMinecraft(_ ticket: String) async throws -> SignedIn? {
        let data = try await call("POST", "/auth/minecraft/poll", body: ["ticket": ticket])
        return pending(data) ? nil : try decoder.decode(SignedIn.self, from: data)
    }
    func signOut() async { _ = try? await call("POST", "/auth/signout") }
}
