package lab.franziska.pixelbridge;

import java.io.IOException;
import java.util.List;

/** Broker is deliberately unconfigured in the developer/community sideload edition. */
final class MyGlassApi {
    static final class Failure extends IOException {
        final int httpStatus;
        Failure(String code,int httpStatus) { super(code); this.httpStatus=httpStatus; }
    }
    MyGlassApi(char[] token) { /* No credential retained and no endpoint exists. */ }
    void heartbeat(Boolean bluetooth,boolean mtls,String state) throws Exception {
        throw unconfigured();
    }
    List<MyGlassWire.Command> claim() throws Exception { throw unconfigured(); }
    List<MyGlassWire.Command> claim(boolean includeCards) throws Exception { throw unconfigured(); }
    void ack(MyGlassWire.Command command,String status,String code) throws Exception {
        throw unconfigured();
    }
    private static Failure unconfigured() { return new Failure("MYGLASS_UNCONFIGURED",0); }
}
