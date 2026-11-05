package es.pablo.periodico;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

final class InkAppearance {
    private static final Object ACCENT = new Object();
    private static final ColorMatrixColorFilter MONO = filter(0f), COLOR = filter(.38f);

    private static ColorMatrixColorFilter filter(float saturation) {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(saturation);
        return new ColorMatrixColorFilter(matrix);
    }
    static boolean color(Context context) {
        return context.getSharedPreferences("settings", 0).getBoolean("color_ink", false);
    }
    static void accent(TextView view) {
        view.setTag(ACCENT);
        view.setTextColor(color(view.getContext()) ? Color.rgb(49, 82, 83) : Color.rgb(32, 32, 29));
    }
    static void image(ImageView view) {
        view.setColorFilter(color(view.getContext()) ? COLOR : MONO);
    }
    static void apply(View view) {
        if (view instanceof ImageView) image((ImageView) view);
        else if (view instanceof TextView && view.getTag() == ACCENT) accent((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }
    static void choose(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("Aspecto")
            .setSingleChoiceItems(new String[]{"Tinta monocroma", "Tinta a color"}, color(activity) ? 1 : 0, (dialog, which) -> {
                activity.getSharedPreferences("settings", 0).edit().putBoolean("color_ink", which == 1).apply();
                apply(activity.getWindow().getDecorView());
                dialog.dismiss();
            }).setNegativeButton("Cancelar", null).show();
    }
}
