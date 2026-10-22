package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.log.QSLRecord;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link DatabaseOpr#doInsertQSLData} must report {@code record.isInvalid} through
 * {@link AfterInsertQSLData#doAfterInsert}, not hard-code {@code false} regardless of it.
 *
 * <p>{@code QSLRecord}'s own field parsing sets {@code isInvalid} when one field (e.g. an
 * unparseable RST_SENT/RST_RCVD or FREQ) couldn't be parsed, but the record is still
 * inserted/updated with that one field dropped -- it isn't rejected outright the way a
 * missing callsign is. A real-world 87 MB ADIF import had 73 such records that the import
 * UI reported as "no invalid records" because this method hard-coded {@code false} for
 * every successful insert/update, ignoring the flag the record itself already carried.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprInsertInvalidTallyTest {

    private static QSLRecord record(String call, boolean invalid) {
        QSLRecord r = new QSLRecord(1_700_000_000_000L, 1_700_000_010_000L
                , "K1ABC", "FN31", call, "IO91"
                , 59, -10, "FT8", 14_074_000L, 1500);
        r.isInvalid = invalid;
        return r;
    }

    private static final class RecordingCallback implements AfterInsertQSLData {
        Boolean lastIsInvalid;
        Boolean lastIsNewQSL;
        int callCount;

        @Override
        public void doAfterInsert(boolean isInvalid, boolean isNewQSL) {
            lastIsInvalid = isInvalid;
            lastIsNewQSL = isNewQSL;
            callCount++;
        }
    }

    @Test
    public void newInsert_validRecord_reportsNotInvalid() {
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        RecordingCallback cb = new RecordingCallback();

        opr.doInsertQSLData(record("K1ABC", false), cb, false);

        assertThat(cb.callCount).isEqualTo(1);
        assertThat(cb.lastIsNewQSL).isTrue();
        assertThat(cb.lastIsInvalid).isFalse();
    }

    @Test
    public void newInsert_recordFlaggedInvalid_stillInsertsButReportsInvalid() {
        // The record still has a callsign (not the missing-callsign early-return case) --
        // it was flagged invalid by QSLRecord itself (e.g. an unparseable RST) but is
        // otherwise insertable, and must still be inserted (the one bad field was already
        // dropped to a sentinel by QSLRecord), just correctly counted as invalid.
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        RecordingCallback cb = new RecordingCallback();

        boolean inserted = opr.doInsertQSLData(record("K2DEF", true), cb, false);

        assertThat(inserted).isTrue();
        assertThat(cb.callCount).isEqualTo(1);
        assertThat(cb.lastIsNewQSL).isTrue();
        assertThat(cb.lastIsInvalid).isTrue();
    }

    @Test
    public void updateOfExistingRecord_flaggedInvalid_reportsInvalidNotNew() {
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        // First pass: insert the record cleanly.
        opr.doInsertQSLData(record("K3GHI", false), null, false);

        // Second pass: same key (call+qso_date+time_on+mode via the constructor's fixed
        // startTime/endTime), now flagged invalid -- this hits the UPDATE branch, not INSERT.
        RecordingCallback cb = new RecordingCallback();
        opr.doInsertQSLData(record("K3GHI", true), cb, false);

        assertThat(cb.callCount).isEqualTo(1);
        assertThat(cb.lastIsNewQSL).isFalse();
        assertThat(cb.lastIsInvalid).isTrue();
    }

    @Test
    public void missingCallsign_stillReportsInvalidTheOldWay() {
        // The pre-existing invalid case (no callsign at all) must be unaffected.
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        RecordingCallback cb = new RecordingCallback();

        boolean inserted = opr.doInsertQSLData(record(null, false), cb, false);

        assertThat(inserted).isFalse();
        assertThat(cb.lastIsInvalid).isTrue();
        assertThat(cb.lastIsNewQSL).isTrue();
    }
}
