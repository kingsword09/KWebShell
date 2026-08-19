package io.github.kingsword09.kwebshell.desktop.internal.ffm;

final class FfmTextException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    FfmTextException(int status, long maximumSize) {
        this(status, maximumSize, null);
    }

    FfmTextException(int status, long maximumSize, Throwable cause) {
        super("FFM UTF-8 conversion failed with status " + status + " and limit " + maximumSize + '.', cause);
        this.status = status;
    }

    int status() {
        return status;
    }
}
