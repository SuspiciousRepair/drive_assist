package com.geely.drivemem.util;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnnexBTest {

    // nal_unit_type lives in the low 5 bits of the byte right after the
    // start code; forbidden_zero_bit (0) and nal_ref_idc (here 3, 0b011)
    // occupy the top 3 bits, matching what a real encoder emits.
    private static byte nalHeader(int type) { return (byte) (0x60 | (type & 0x1F)); }

    @Test public void splitsThreeUnitsWithThreeByteStartCodes() {
        byte[] data = {
            0,0,1, nalHeader(7), 0x11, 0x22,          // SPS, 3 bytes payload
            0,0,1, nalHeader(8), 0x33,                 // PPS, 1 byte payload
            0,0,1, nalHeader(5), 0x44, 0x55, 0x66,     // IDR slice
        };
        List<AnnexB.Nal> nals = AnnexB.split(AnnexB.of(data));
        assertEquals(3, nals.size());
        assertEquals(7, AnnexB.type(AnnexB.of(data), nals.get(0)));
        assertEquals(8, AnnexB.type(AnnexB.of(data), nals.get(1)));
        assertEquals(5, AnnexB.type(AnnexB.of(data), nals.get(2)));
        assertEquals(data.length, nals.get(2).end); // last unit runs to EOF
    }

    @Test public void fourByteStartCodeIsAlsoFound() {
        byte[] data = {
            0,0,0,1, nalHeader(7), 0x11,
            0,0,0,1, nalHeader(1), 0x22, 0x33,
        };
        List<AnnexB.Nal> nals = AnnexB.split(AnnexB.of(data));
        assertEquals(2, nals.size());
        // The leading extra zero of the 4-byte code is swallowed as a
        // trailing byte of the PREVIOUS unit -- harmless, spec-legal.
        assertEquals(7, AnnexB.type(AnnexB.of(data), nals.get(0)));
        assertEquals(1, AnnexB.type(AnnexB.of(data), nals.get(1)));
    }

    @Test public void dashRecorderSegmentShape() {
        // SPS + PPS once, then a run of slices -- exactly what DashRecorder's
        // Seg writes: writeCsd() once at construction, write() per frame
        // after that, never repeating SPS/PPS before a later IDR.
        byte[] data = {
            0,0,1, nalHeader(7), 1,2,3,
            0,0,1, nalHeader(8), 4,
            0,0,1, nalHeader(5), 5,6,7,8,   // IDR (keyframe)
            0,0,1, nalHeader(1), 9,10,      // P-frame
            0,0,1, nalHeader(1), 11,
        };
        List<AnnexB.Nal> nals = AnnexB.split(AnnexB.of(data));
        assertEquals(5, nals.size());
        int[] expectedTypes = {7, 8, 5, 1, 1};
        for (int i = 0; i < nals.size(); i++) {
            assertEquals("nal " + i, expectedTypes[i], AnnexB.type(AnnexB.of(data), nals.get(i)));
        }
    }

    @Test public void emptySourceYieldsNoNals() {
        assertTrue(AnnexB.split(AnnexB.of(new byte[0])).isEmpty());
    }

    @Test public void noStartCodeAtAllYieldsNoNals() {
        byte[] data = {1, 2, 3, 4, 5};
        assertTrue(AnnexB.split(AnnexB.of(data)).isEmpty());
    }

    @Test public void truncatedFinalNalStillIncluded() {
        // A crash can cut off mid-NAL; the last unit is still returned,
        // just short -- ClipRecovery decides whether a too-short trailing
        // sample is worth keeping, not this class.
        byte[] data = { 0,0,1, nalHeader(1) }; // header byte only, no payload
        List<AnnexB.Nal> nals = AnnexB.split(AnnexB.of(data));
        assertEquals(1, nals.size());
        assertEquals(1, nals.get(0).length() - 3); // 1 byte of "payload" (the header itself)
    }

    @Test public void streamingReaderKeepsOnlyOneNalAndNormalizesFourByteCodes() throws Exception {
        File f = File.createTempFile("annexb", ".h264");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[] {0,0,0,1, nalHeader(7), 1, 0,0,1, nalHeader(8), 2});
        }
        try (AnnexB.Reader r = new AnnexB.Reader(f)) {
            assertEquals(7, AnnexB.type(r.next()));
            assertEquals(8, AnnexB.type(r.next()));
            assertEquals(null, r.next());
        } finally { f.delete(); }
    }

    @Test public void readsFirstMacroblockExpGolomb() {
        assertEquals(0, AnnexB.firstMbInSlice(new byte[] {0,0,1, nalHeader(1), (byte) 0x80}));
        // Exp-Golomb code 010 encodes value one.
        assertEquals(1, AnnexB.firstMbInSlice(new byte[] {0,0,1, nalHeader(1), (byte) 0x40}));
    }
}
