package com.geely.modehelper;

/** Dependency-free test runner for {@link MediaAccessPolicy}. */
public final class MediaAccessPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String c = MediaAccessPolicy.COMPONENT;
        check(c.equals(MediaAccessPolicy.edit(null, true)), "grant on an empty list");
        check(c.equals(MediaAccessPolicy.edit("", true)), "grant on an empty string");
        check(("a/b:" + c).equals(MediaAccessPolicy.edit("a/b", true)), "grant keeps the other entries");
        check(MediaAccessPolicy.edit("a/b:" + c, true) == null, "already granted: no write");
        check("a/b:x/y".equals(MediaAccessPolicy.edit("a/b:" + c + ":x/y", false)), "revoke keeps the others, in order");
        check("".equals(MediaAccessPolicy.edit(c, false)), "revoke the only entry");
        check(MediaAccessPolicy.edit("a/b", false) == null, "not there: no write");
        check(("a/b:" + c).equals(MediaAccessPolicy.edit("a/b::", true)), "empty entries dropped");
        check(c.startsWith("com.geely.drivemem/"), "only Drive Assist can be granted");
    }
}
