package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;

import org.junit.Test;

public class XieGuG106RigTest {
    @Test
    public void catPttOnNeedsKeepAlive() {
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(true, ControlMode.CAT)).isTrue();
    }

    @Test
    public void receiveAndHardwarePttDoNotNeedKeepAlive() {
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(false, ControlMode.CAT)).isFalse();
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(true, ControlMode.RTS)).isFalse();
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(true, ControlMode.DTR)).isFalse();
    }

    @Test
    public void keepAliveRunsSafelyBeforeTwoSecondRadioTimeout() {
        assertThat(XieGuG106Rig.PTT_KEEPALIVE_MS).isLessThan(2_000L);
    }
}
