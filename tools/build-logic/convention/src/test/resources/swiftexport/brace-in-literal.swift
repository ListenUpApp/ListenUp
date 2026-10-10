import KotlinRuntime

extension ExportedKotlinPackages.com.calypsan.listenup.client.domain.model {
    public final class Alpha: KotlinRuntime.KotlinBase {
        public func boom() {
            fatalError("unbalanced { in a message")
        }
    }
    public final class Beta: KotlinRuntime.KotlinBase {
    }
}

public enum Tier: KotlinRuntime.KotlinBase {
    case GOLD
    /// closes with } early
    case SILVER_PLUS

    public var description: Swift.String {
        switch self {
        case .GOLD: return "GOLD"
        case .SILVER_PLUS: return "SILVER_PLUS"
        }
    }
}
