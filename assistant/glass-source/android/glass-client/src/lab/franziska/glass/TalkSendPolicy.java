package lab.franziska.glass;

/** Local writer policy only: no new wire fields, queues, threads or audio dropping. */
final class TalkSendPolicy {
    private boolean flowTurnUsed, photoTurnUsed;

    /** Called by the single owned writer while the Activity owns queue selection. */
    boolean takeFlow(boolean flowPending, boolean regularPending) {
        if (!flowPending || regularPending && flowTurnUsed) return false;
        flowTurnUsed = true;
        return true;
    }
    void regularTaken() { flowTurnUsed = false; photoTurnUsed = false; }
    boolean takePhoto(boolean regularPending) {
        if(regularPending && photoTurnUsed)return false;
        photoTurnUsed=true;flowTurnUsed=false;return true;
    }

    /** One Talk lifetime; speaker completion never enters the Activity/UI monitor.
     * Immutable CAS snapshots order accepted-before-play publication and clear/Stop.
     * The existing owned writer samples this meter; it adds no timer or work queue. */
    static final class Playback {
        static final class State {
            final int generation,accepted,completed;
            final boolean active,failed;
            State(int generation,int accepted,int completed,boolean active,boolean failed) {
                this.generation=generation;this.accepted=accepted;this.completed=completed;
                this.active=active;this.failed=failed;
            }
        }
        private final java.util.concurrent.atomic.AtomicReference<State> state =
                new java.util.concurrent.atomic.AtomicReference<State>(new State(0,0,0,true,false));
        State snapshot() { return state.get(); }
        private State failed(State s) { return new State(s.generation,s.accepted,s.completed,s.active,true); }
        boolean admit(int bytes) {
            for(;;) {
                State s=state.get();if(!s.active||s.failed)return false;
                boolean valid=bytes>=2&&(bytes&1)==0&&bytes<=lab.franziska.protocol.TalkWire.MAX_PCM
                        &&s.accepted<=lab.franziska.protocol.TalkWire.MAX_OUTPUT_CREDIT_BYTES-bytes;
                State next=valid?new State(s.generation,s.accepted+bytes,s.completed,true,false):failed(s);
                if(state.compareAndSet(s,next))return valid;
            }
        }
        void completed(int bytes,int generation) {
            for(;;) {
                State s=state.get();
                if(!s.active||s.failed||generation!=s.generation)return;
                boolean valid=bytes>=0&&(bytes&1)==0&&bytes<=s.accepted-s.completed;
                State next=valid?new State(s.generation,s.accepted,s.completed+bytes,true,false):failed(s);
                if(state.compareAndSet(s,next))return;
            }
        }
        boolean clear(int generation) {
            for(;;) {
                State s=state.get();if(!s.active||s.failed)return false;
                boolean valid=(long)s.generation+1L==generation;
                State next=valid?new State(generation,s.accepted,s.accepted,true,false):failed(s);
                if(state.compareAndSet(s,next))return valid;
            }
        }
        void revoke() {
            for(;;) {
                State s=state.get();if(!s.active)return;
                if(state.compareAndSet(s,new State(s.generation,s.accepted,s.completed,false,s.failed)))return;
            }
        }
    }

    /** Activity-confined, replaced for each Talk. The existing writer polls the tail;
     * there is no delayed callback which can survive Stop, clear or reconnect. */
    static final class Credits {
        static final int STEP_BYTES = 11520; // 240 ms at 24 kHz mono PCM16.
        static final long MAX_DELAY_MS = 240L;
        private int published = -1;
        private long publishedAt;

        boolean publish(int releasedBytes, long now, boolean force) {
            if (releasedBytes < 0 || releasedBytes < published) return false;
            if (published >= 0 && !force && (releasedBytes == published
                    || (long) releasedBytes - published < STEP_BYTES
                        && now - publishedAt < MAX_DELAY_MS)) return false;
            published = releasedBytes;
            publishedAt = now;
            return true;
        }
    }

    /** Fixed-category, bounded first-failure context; never receives payloads or IDs. */
    static final class Trace {
        private int admitted, selectedMic, selectedFlow, flowRun, maxFlowRun;
        private long lastMicWrite = -1L, phaseAt, maxAdmissionWait, maxQueueAge;
        private long encodeMs, writeMs, maxEncodeMs, maxWriteMs;
        private int phase; // 0 idle, 1 selected/validation, 2 encoding, 3 writing.
        private boolean microphone;
        synchronized void admitted(long lockWait) {
            admitted++;
            maxAdmissionWait = Math.max(maxAdmissionWait, lockWait);
        }
        synchronized void selected(boolean mic, boolean flow, long now, long queueAge) {
            microphone = mic;
            if (mic) selectedMic++;
            if (flow) { selectedFlow++; flowRun++; maxFlowRun = Math.max(maxFlowRun, flowRun); }
            else flowRun = 0;
            maxQueueAge = Math.max(maxQueueAge, queueAge);
            phase = 1; phaseAt = now;
        }
        synchronized void encoding(long now) { phase = 2; phaseAt = now; }
        synchronized void encoded(long now) {
            encodeMs = now - phaseAt; maxEncodeMs = Math.max(maxEncodeMs, encodeMs);
            phase = 3; phaseAt = now;
        }
        synchronized void written(long now) {
            writeMs = now - phaseAt; maxWriteMs = Math.max(maxWriteMs, writeMs);
            if (microphone) lastMicWrite = now;
            phase = 0; phaseAt = now;
        }
        synchronized String snapshot(long now) {
            return " MIC_ADMITTED=" + admitted + " MIC_SELECTED=" + selectedMic
                    + " FLOW_SELECTED=" + selectedFlow + " MAX_FLOW_RUN=" + maxFlowRun
                    + " ADMISSION_LOCK_MAX_MS=" + maxAdmissionWait + " QUEUE_AGE_MAX_MS=" + maxQueueAge
                    + " ENCODE_MS=" + encodeMs + " ENCODE_MAX_MS=" + maxEncodeMs
                    + " WRITE_MS=" + writeMs + " WRITE_MAX_MS=" + maxWriteMs
                    + " WRITE_PHASE=" + phase + " PHASE_AGE_MS=" + (phase == 0 ? 0 : now - phaseAt)
                    + " LAST_MIC_WRITE_AGE_MS=" + (lastMicWrite < 0 ? -1 : now - lastMicWrite);
        }
    }
}
