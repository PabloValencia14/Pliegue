package es.pablo.periodico;

final class ArticleColumns {
    static int[] widths(int totalWidth, int dividerWidth, float[] weights) {
        int available = totalWidth - dividerWidth * (weights.length - 1);
        float totalWeight = 0;
        for (float weight : weights) totalWeight += weight;
        int[] widths = new int[weights.length];
        int used = 0;
        for (int i = 0; i < weights.length; i++) {
            widths[i] = i == weights.length - 1 ? available - used : (int)(available * weights[i] / totalWeight);
            used += widths[i];
        }
        return widths;
    }
    static int shortest(int[] heights) {
        int selected = 0;
        for (int i = 1; i < heights.length; i++) if (heights[i] < heights[selected]) selected = i;
        return selected;
    }
}
