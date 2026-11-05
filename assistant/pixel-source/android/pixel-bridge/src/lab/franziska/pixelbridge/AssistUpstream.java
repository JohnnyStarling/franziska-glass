package lab.franziska.pixelbridge;

/** One synchronous attempt on a worker; never owns sensors or retries. Caller wipes media. */
public interface AssistUpstream {
    String execute(String url, String agentId, String requestId, byte[] jpeg, byte[] wav) throws Exception;
    void cancel();
}
