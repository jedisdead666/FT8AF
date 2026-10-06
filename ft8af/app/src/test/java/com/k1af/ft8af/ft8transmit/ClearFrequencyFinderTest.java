package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link ClearFrequencyFinder} — the clear-CQ-slot occupancy and
 * scoring logic behind issue #418 (see docs/clear-cq-slot-selection.md). Plain
 * JUnit: pure arithmetic, deterministic, no Android types.
 */
public class ClearFrequencyFinderTest {

    private static final long FT8_SLOT_MS = 15_000L;
    private static final long T0 = 1_000_000L;

    /** A finder with the shipping defaults. */
    private static ClearFrequencyFinder finder() {
        return new ClearFrequencyFinder();
    }

    private static void occupy(ClearFrequencyFinder f, float freq, long utc) {
        f.record(freq, utc, FT8_SLOT_MS);
    }

    // ---- occupancy intervals + guard spacing ---------------------------------

    @Test
    public void signalBlocksItsBandwidthPlusGuard() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);

        // The signal itself: occupied.
        assertThat(f.isOccupied(1500f)).isTrue();
        // Candidate whose 50 Hz interval touches [1490, 1560] (10 Hz guard): occupied.
        assertThat(f.isOccupied(1440f)).isTrue();  // [1440,1490] touches 1490
        assertThat(f.isOccupied(1560f)).isTrue();  // [1560,1610] touches 1560
        // Just beyond the guard on either side: clear.
        assertThat(f.isOccupied(1439f)).isFalse();
        assertThat(f.isOccupied(1561f)).isFalse();
    }

    @Test
    public void emptyHistory_isNeverOccupied() {
        // No information is not "occupied" — and selection must not move either.
        ClearFrequencyFinder f = finder();
        assertThat(f.isOccupied(1500f)).isFalse();
        assertThat(f.selectClearOffset(1500f)).isNull();
    }

    // ---- multi-cycle window ---------------------------------------------------

    @Test
    public void observationsExpireAfterTheHistoryWindow() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        assertThat(f.isOccupied(1500f)).isTrue();

        // 4-cycle window (default): a new observation 5 slots later evicts it.
        occupy(f, 300f, T0 + 5 * FT8_SLOT_MS);
        assertThat(f.isOccupied(1500f)).isFalse();
        assertThat(f.observationCount()).isEqualTo(1);
    }

    @Test
    public void windowFollowsTheModeSlotLength() {
        // Same timestamps, FT4 slot length (7.5 s): 4 cycles is only 30 s, so an
        // observation 40 s old is gone where FT8's 60 s window would keep it.
        ClearFrequencyFinder f = finder();
        f.record(1500f, T0, 7_500L);
        f.record(300f, T0 + 40_000L, 7_500L);
        assertThat(f.isOccupied(1500f)).isFalse();
    }

    @Test
    public void clearDropsEverything() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        f.clear();
        assertThat(f.observationCount()).isEqualTo(0);
        assertThat(f.selectClearOffset(1500f)).isNull(); // back to no-information
    }

    @Test
    public void clearAlsoResetsTheHoldDown() {
        // A band/mode hop invalidates the hold-down along with the history: the
        // old band's move must not suppress the first relocation on the new band.
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        f.noteMoved(T0);
        f.clear();

        occupy(f, 1500f, T0 + FT8_SLOT_MS); // new band, occupied right away
        assertThat(f.shouldRelocate(1500f, T0 + FT8_SLOT_MS)).isTrue();
    }

    // ---- selection ------------------------------------------------------------

    @Test
    public void keepsTheCurrentOffsetWhenItIsClear() {
        ClearFrequencyFinder f = finder();
        occupy(f, 500f, T0);
        // 1500 is nowhere near 500: no move suggested (a gratuitous QSY loses
        // callers that had already spotted us).
        assertThat(f.selectClearOffset(1500f)).isNull();
    }

    @Test
    public void movesOffAnOccupiedOffsetIntoAClearSpot() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        Float pick = f.selectClearOffset(1510f); // we sit inside the occupied interval
        assertThat(pick).isNotNull();
        assertThat(f.isOccupied(pick)).isFalse();
        // The pick's own interval fits the candidate range.
        assertThat(pick).isAtLeast(ClearFrequencyFinder.DEFAULT_MIN_OFFSET_HZ);
        assertThat(pick + ClearFrequencyFinder.SIGNAL_BANDWIDTH_HZ)
                .isAtMost(ClearFrequencyFinder.DEFAULT_MAX_OFFSET_HZ);
    }

    @Test
    public void prefersTheWidestGap_thenTheBandCenter() {
        // Occupy everything except two gaps: a narrow one near 600 and a wide
        // one around 2000. The pick must land in the wide gap.
        ClearFrequencyFinder f = finder();
        for (float freq = 200f; freq <= 2800f; freq += 60f) {
            boolean inNarrowGap = freq > 550f && freq < 700f;
            boolean inWideGap = freq > 1700f && freq < 2300f;
            if (!inNarrowGap && !inWideGap) {
                occupy(f, freq, T0);
            }
        }
        Float pick = f.selectClearOffset(300f);
        assertThat(pick).isNotNull();
        assertThat(pick).isGreaterThan(1700f);
        assertThat(pick).isLessThan(2300f);
    }

    @Test
    public void centerTieBreak_picksMidBandOnAnEmptyishBand() {
        // One lone signal at 400: huge clear space everywhere, so many candidates
        // hit the clearance cap. The tie-break must pull the pick toward 1500,
        // not the band edges.
        ClearFrequencyFinder f = finder();
        occupy(f, 400f, T0);
        Float pick = f.selectClearOffset(410f); // current is occupied
        assertThat(pick).isNotNull();
        assertThat(Math.abs(pick - ClearFrequencyFinder.PREFERRED_CENTER_HZ))
                .isLessThan(300f);
    }

    @Test
    public void fullBand_staysPutWhenNothingIsBetter() {
        // Signals every 40 Hz across the whole candidate range: every candidate
        // interval intersects something, so clearance is 0 everywhere — no move
        // is suggested (hopping between equally-bad spots helps nobody).
        ClearFrequencyFinder f = finder();
        for (float freq = 150f; freq <= 2850f; freq += 40f) {
            occupy(f, freq, T0);
        }
        assertThat(f.selectClearOffset(1500f)).isNull();
    }

    // ---- retry / hold-down ------------------------------------------------------

    @Test
    public void relocatesOnlyWhenOccupied() {
        ClearFrequencyFinder f = finder();
        occupy(f, 500f, T0);
        assertThat(f.shouldRelocate(1500f, T0)).isFalse(); // clear: stay
        assertThat(f.shouldRelocate(510f, T0)).isTrue();   // occupied: go
    }

    @Test
    public void holdDownPacesRepeatedMoves() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        assertThat(f.shouldRelocate(1500f, T0)).isTrue();
        f.noteMoved(T0);

        // Still occupied one cycle later: hold-down (45 s default) blocks the hop.
        occupy(f, 1500f, T0 + FT8_SLOT_MS);
        assertThat(f.shouldRelocate(1500f, T0 + FT8_SLOT_MS)).isFalse();

        // Past the hold-down: allowed again.
        occupy(f, 1500f, T0 + ClearFrequencyFinder.DEFAULT_MOVE_HOLDDOWN_MS);
        assertThat(f.shouldRelocate(1500f,
                T0 + ClearFrequencyFinder.DEFAULT_MOVE_HOLDDOWN_MS)).isTrue();
    }

    // ---- the CQ-idle gate (wiring predicate on FT8TransmitSignal) --------------

    // ---- minimum auto TX audio frequency (operator floor) --------------------

    @Test
    public void floor_keepsPicksAtOrAboveIt() {
        ClearFrequencyFinder f = finder();
        // Busy above the floor, wide open below it: without a floor the pick
        // would land in the low half.
        for (float hz = 1500f; hz <= 2700f; hz += 100f) {
            occupy(f, hz, T0);
        }
        assertThat(f.selectClearOffset(1510f)).isLessThan(1500f);

        ClearFrequencyFinder floored = finder();
        occupy(floored, 2000f, T0);
        Float pick = floored.selectClearOffset(2010f, 1500f);
        assertThat(pick).isNotNull();
        assertThat(pick).isAtLeast(1500f);
        assertThat(floored.isOccupied(pick)).isFalse();
    }

    @Test
    public void floor_staysPutWhenNothingAboveItIsBetter() {
        ClearFrequencyFinder f = finder();
        // Everything from the floor up is occupied; the free space below the
        // floor must not be used, so there is nowhere better to go.
        for (float hz = 1450f; hz <= 2800f; hz += 50f) {
            occupy(f, hz, T0);
        }
        assertThat(f.selectClearOffset(2000f, 1500f)).isNull();
    }

    @Test
    public void floor_movesAClearCurrentOffsetThatSitsBelowIt() {
        ClearFrequencyFinder f = finder();
        occupy(f, 2500f, T0);
        // 800 Hz is clear, and without a floor that means "stay"...
        assertThat(f.selectClearOffset(800f)).isNull();
        // ...but it is below the floor, so the picker lifts it out.
        Float pick = f.selectClearOffset(800f, 1500f);
        assertThat(pick).isNotNull();
        assertThat(pick).isAtLeast(1500f);
        assertThat(f.isOccupied(pick)).isFalse();
    }

    @Test
    public void floor_appliesEvenWithNoHistory() {
        ClearFrequencyFinder f = finder();
        Float pick = f.selectClearOffset(800f, 1500f);
        // Nothing heard: the middle of the allowed range, not the floor edge.
        assertThat(pick).isWithin(10f).of((1500f + 2750f) / 2f);
        // An offset already above the floor still needs history to move.
        assertThat(f.selectClearOffset(1800f, 1500f)).isNull();
    }

    @Test
    public void floor_atOrBelowTheGridMinimumIsNoFloor() {
        ClearFrequencyFinder f = finder();
        // "Off" (0) must behave exactly like the unfloored call, including
        // for an offset under the grid minimum and with no history.
        assertThat(f.selectClearOffset(100f, 0f)).isNull();
        occupy(f, 1500f, T0);
        assertThat(f.selectClearOffset(1510f, 0f)).isEqualTo(f.selectClearOffset(1510f));
        assertThat(f.selectClearOffset(100f, 0f)).isNull();
    }

    @Test
    public void floor_isCappedSoACandidateAlwaysFits() {
        ClearFrequencyFinder f = finder();
        Float pick = f.selectClearOffset(800f, 9000f);
        assertThat(pick).isNotNull();
        assertThat(pick + ClearFrequencyFinder.SIGNAL_BANDWIDTH_HZ)
                .isAtMost(ClearFrequencyFinder.DEFAULT_MAX_OFFSET_HZ);
    }

    @Test
    public void minAutoTxChoices_snapStoredValuesOntoTheList() {
        int[] choices = ClearFrequencyFinder.minAutoTxChoicesHz();
        assertThat(choices[0]).isEqualTo(0); // index 0 is "Off"
        for (int i = 0; i < choices.length; i++) {
            assertThat(ClearFrequencyFinder.minAutoTxChoiceIndex(choices[i])).isEqualTo(i);
            assertThat(ClearFrequencyFinder.clampMinAutoTxHz(choices[i])).isEqualTo(choices[i]);
        }
        // Garbage from a settings import: negative, off-grid, oversized.
        assertThat(ClearFrequencyFinder.clampMinAutoTxHz(-5)).isEqualTo(0);
        assertThat(ClearFrequencyFinder.clampMinAutoTxHz(1499)).isEqualTo(1000);
        assertThat(ClearFrequencyFinder.clampMinAutoTxHz(99_999))
                .isEqualTo(choices[choices.length - 1]);
        // The accessor hands out a copy, not the backing array.
        choices[0] = 123;
        assertThat(ClearFrequencyFinder.minAutoTxChoicesHz()[0]).isEqualTo(0);
    }

    @Test
    public void autoPickGate_requiresEnabledActivatedIdleCq() {
        TransmitCallsign cq = new TransmitCallsign(1, 0, "CQ", 0);
        TransmitCallsign station = new TransmitCallsign(1, 0, "K1ABC", 0);

        // The happy path: enabled, armed, not transmitting, CQ baseline, no target.
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 6, cq)).isTrue();
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 6, null)).isTrue();

        // Each precondition individually kills it.
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                false, true, false, 6, cq)).isFalse();   // setting off
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, false, false, 6, cq)).isFalse();   // not armed
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, true, 6, cq)).isFalse();     // mid-transmission
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 2, station)).isFalse(); // mid-QSO: never QSY
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 6, station)).isFalse(); // caller locked
    }
}
