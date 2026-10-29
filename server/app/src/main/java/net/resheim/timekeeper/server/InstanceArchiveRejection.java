package net.resheim.timekeeper.server;

import java.io.IOException;

/** Fixed safe archive failure categories may cross private staging boundaries. */
final class InstanceArchiveRejection extends IOException {
    enum Code { INVALID_INSTANCE_AUTHENTICATION }
    private final Code code;
    InstanceArchiveRejection(Code code){super(code.name());this.code=code;}
    Code code(){return code;}
}
