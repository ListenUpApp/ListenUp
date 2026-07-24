import KotlinRuntime

public enum ContributorRole: KotlinRuntime.KotlinBase {
    case AUTHOR
    case NARRATOR
    case FIRST_NAME

    public var description: Swift.String {
        switch self {
        case .AUTHOR: return "AUTHOR"
        case .NARRATOR: return "NARRATOR"
        case .FIRST_NAME: return "FIRST_NAME"
        }
    }
}
