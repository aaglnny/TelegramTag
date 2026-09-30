package org.telegram.messenger;

public final class SavedLinkReference {

    public static final int NO_LINK = 0;
    public static final int UNSUPPORTED = 1;
    public static final int PARSED = 2;
    public static final int PENDING = 0;
    public static final int AVAILABLE = 1;
    public static final int UNAVAILABLE = 2;
    public static final int FAILED = 3;

    public final long userId;
    public final int savedMessageId;
    public final String contentSignature;
    public final String originalUrl;
    public final String username;
    public final long channelId;
    public final int sourceMessageId;
    public final int parseState;
    public final long sourceDialogId;
    public final int state;
    public final long lastSuccessAt;

    SavedLinkReference(long userId, int savedMessageId, String contentSignature, String originalUrl,
                       String username, long channelId, int sourceMessageId, int parseState,
                       long sourceDialogId, int state, long lastSuccessAt) {
        this.userId = userId;
        this.savedMessageId = savedMessageId;
        this.contentSignature = contentSignature;
        this.originalUrl = originalUrl;
        this.username = username;
        this.channelId = channelId;
        this.sourceMessageId = sourceMessageId;
        this.parseState = parseState;
        this.sourceDialogId = sourceDialogId;
        this.state = state;
        this.lastSuccessAt = lastSuccessAt;
    }

    public SavedLinkReference withSource(long dialogId, int state, long lastSuccessAt) {
        return new SavedLinkReference(userId, savedMessageId, contentSignature, originalUrl, username,
                channelId, sourceMessageId, parseState, dialogId, state, lastSuccessAt);
    }
}
