package com.k1af.ft8af.rigs;

import com.k1af.ft8af.database.ControlMode;

/**
 * Xiegu G106/G106C CI-V driver.
 *
 * <p>The G106 releases CAT-controlled PTT after roughly two seconds unless the
 * command is refreshed. FT8 transmission lasts 12.64 seconds, so repeat the
 * PTT-on frame once per second while transmitting. RTS and DTR keying do not
 * need this workaround.</p>
 */
public class XieGuG106Rig extends XieGuRig {
    static final long PTT_KEEPALIVE_MS = 1_000L;

    public XieGuG106Rig(int civAddress) {
        super(civAddress, PTT_KEEPALIVE_MS);
    }

    static boolean needsCatPttKeepAlive(boolean pttOn, int controlMode) {
        return pttOn && controlMode == ControlMode.CAT;
    }

    @Override
    protected void onPollingWhileTransmitting() {
        if (needsCatPttKeepAlive(isPttOn(), getControlMode())) {
            // Runs on XieGuRig's existing polling timer, keeping all serial
            // commands on the same ordered path as its meter/frequency polls.
            super.setPTT(true);
        }
    }

    @Override
    public String getName() {
        return "XIEGU G106 series";
    }
}
