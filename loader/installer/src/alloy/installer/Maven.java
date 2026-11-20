package alloy.installer;

/** Maven coordinates as launchers use them: group:artifact:version[:classifier][@ext]. */
final class Maven {
    private Maven() { }

    /** relative maven path (forward slashes) of a coordinate. */
    static String path(String coord) {
        String ext = "jar";
        int at = coord.indexOf('@');
        if (at >= 0) { ext = coord.substring(at + 1); coord = coord.substring(0, at); }
        String[] p = coord.split(":");
        if (p.length < 3 || p.length > 4) throw new IllegalArgumentException("bad maven coordinate: " + coord);
        String file = p[1] + "-" + p[2] + (p.length == 4 ? "-" + p[3] : "") + "." + ext;
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    /** coordinate with the group replaced (artifact, version, classifier and extension kept). */
    static String regroup(String coord, String group) {
        return group + coord.substring(coord.indexOf(':'));
    }

    /** group:artifact[:classifier], the key launchers use to tell two versions of one library apart. */
    static String key(String coord) {
        String c = coord.contains("@") ? coord.substring(0, coord.indexOf('@')) : coord;
        String[] p = c.split(":");
        return p[0] + ":" + p[1] + (p.length == 4 ? ":" + p[3] : "");
    }
}
