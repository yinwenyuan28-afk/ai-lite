package io.github.yinwenyuan28.ailite.assembly;

/**
 * afterTurn 的决定。有一个要求停止就停；没人停止又有人要求继续，就再来一轮。
 */
public final class TurnDecision {

    enum Kind {
        STOP,
        CONTINUE,
        MODEL_DECIDES
    }

    private final Kind kind;

    private TurnDecision(Kind kind) {
        this.kind = kind;
    }

    public static TurnDecision stop() {
        return new TurnDecision(Kind.STOP);
    }

    public static TurnDecision continueRound() {
        return new TurnDecision(Kind.CONTINUE);
    }

    public static TurnDecision modelDecides() {
        return new TurnDecision(Kind.MODEL_DECIDES);
    }

    Kind kind() {
        return kind;
    }
}
