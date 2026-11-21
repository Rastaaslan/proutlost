package fr.proutlost.director.catalog;

final class InvalidContent extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    final String pointer;
    InvalidContent(String pointer, String detail) { super(detail); this.pointer = pointer; }
    static void require(boolean valid, String pointer, String detail) {
        if (!valid) throw new InvalidContent(pointer, detail);
    }
}
