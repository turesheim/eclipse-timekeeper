package net.resheim.timekeeper.connect;

/** Expected failures carry fixed codes, never provider responses or credentials. */
final class ConnectFailure extends RuntimeException {
    final int status;
    final String code;
    ConnectFailure(int status,String code){super(code);this.status=status;this.code=code;}
}
