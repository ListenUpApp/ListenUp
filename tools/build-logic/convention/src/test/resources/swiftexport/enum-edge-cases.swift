import KotlinRuntime

public enum OrganizeSeriesPrefix: KotlinRuntime.KotlinBase {
    case BOOK_N_DASH
    case NONE

    public var description: Swift.String {
        switch self {
        case .BOOK_N_DASH: return "BOOK_N_DASH"
        case .NONE: return "NONE"
        }
    }
}

public enum Grade: KotlinRuntime.KotlinBase {
    case A
    case BEST
}
