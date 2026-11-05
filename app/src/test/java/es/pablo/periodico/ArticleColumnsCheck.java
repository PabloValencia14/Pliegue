package es.pablo.periodico;

public final class ArticleColumnsCheck {
    public static void main(String[] args) {
        int[] widths = ArticleColumns.widths(3067, 2, new float[]{1.75f, 1, 1});
        require(widths[0] > widths[1] && widths[0] + widths[1] + widths[2] + 4 == 3067, "Column widths lost pixels or hierarchy");
        require(ArticleColumns.widths(457, 2, new float[]{1})[0] == 457, "Single column width changed");
        int[] heights = {600, 180, 340};
        for (int article : new int[]{320, 170, 150}) heights[ArticleColumns.shortest(heights)] += article;
        require(heights[0] == 600 && heights[1] == 650 && heights[2] == 510, "Measured short columns were not filled");
        require(ArticleColumns.shortest(new int[]{300, 100, 100}) == 1, "Ties are unstable");
        System.out.println("ArticleColumnsCheck: exact widths, editorial proportions and measured-height packing passed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
