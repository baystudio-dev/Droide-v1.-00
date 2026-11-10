package android.webkit;

/** Test-only fixture in the framework package because WebResourceError's constructor is package-private. */
public final class DroideTestWebResourceError extends WebResourceError {
    private final int errorCode;
    private final CharSequence description;

    public DroideTestWebResourceError(int errorCode, CharSequence description) {
        this.errorCode = errorCode;
        this.description = description;
    }

    @Override
    public int getErrorCode() {
        return errorCode;
    }

    @Override
    public CharSequence getDescription() {
        return description;
    }
}
