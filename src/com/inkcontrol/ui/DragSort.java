package com.inkcontrol.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * 拖动排序：列表每行左侧一个「三条横线」把手，按住把手上下拖，松手把这一行放到落点。
 *
 * <p>拖动过程只平移视图，不插入／移除子视图——拖动中的视图一旦被 remove 再 add，Android 会把
 * 后续 MOVE 当成取消，拖到一半就断。被拖的那行跟着手指走，其它行按落点让位（整行地上下平移），
 * 中间留出一个空位；被拖的那行由容器最后绘制，所以压在其它行上面，不会出现两行文字叠在一起。
 * 松手后清掉所有平移再交给调用方重排数据，调用方通常紧接着重建页面，看不到回弹。
 *
 * <p>把手必须是被拖行的直接子视图，该行是 {@link #container} 返回的容器的直接子视图
 * （标题、说明、按钮混在同一层会把序号算偏）。行高可以不同，让位距离按各自原布局位置算。
 */
final class DragSort {
    interface OnDrop {
        /** 把第 {@code from} 行挪到第 {@code to} 位；两者相同表示没动。 */
        void onDrop(int from, int to);
    }

    private DragSort() { }

    /**
     * 只在里面放「可拖动的行」的容器。绘制顺序改成把被拖的那行放到最后，
     * 代替桌面那种 z 轴抬升（墨水屏不用阴影，elevation 会抖出灰块）。
     */
    static LinearLayout container(Context context) {
        return new List(context);
    }

    /** 三条横线的把手，纯绘制，不依赖字体里有没有对应字形。 */
    static View handle(Context context, Ink ink) {
        Handle view = new Handle(context, ink);
        view.setContentDescription("按住拖动排序");
        view.setBackground(ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                ink.box(Ink.PAPER, Ink.INK, 2, 8)));
        return view;
    }

    static void attach(View handle, View item, OnDrop onDrop) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRaw;
            private int from;
            private int target;
            private Drawable resting;
            private boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (!(item.getParent() instanceof List)) return false;
                List container = (List) item.getParent();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        from = target = container.indexOfChild(item);
                        if (from < 0) return false;
                        downRaw = event.getRawY();
                        dragging = true;
                        // 不让外面的滚动容器把这次滑动抢走，否则拖到一半页面跟着滚。
                        ViewGroup parent = (ViewGroup) container.getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                        // 拿起来的样子：白底黑框盖在列表上。
                        resting = item.getBackground();
                        item.setBackground(inkLift(view));
                        container.front(item);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        item.setTranslationY(event.getRawY() - downRaw);
                        int next = target(container, from, event.getRawY());
                        if (next != target) {
                            target = next;
                            makeGap(container, from, target, item);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        int to = target;
                        finish(container, item, resting, dragging);
                        dragging = false;
                        if (to != from) onDrop.onDrop(from, to);
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        finish(container, item, resting, dragging);
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * 手指所在位置换算成「列表里第几位」：比它中线靠上的行都算在它前面。
     * 用原布局里的 top/bottom（不受 translationY 影响），所以让位动画不会反馈到判定上。
     */
    private static int target(ViewGroup container, int from, float rawY) {
        int[] location = new int[2];
        container.getLocationOnScreen(location);
        float y = rawY - location[1];
        int slot = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (i == from) continue;
            View child = container.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (y > (child.getTop() + child.getBottom()) / 2f) slot++;
        }
        return slot;
    }

    /**
     * 让位：整行搬到前／后一格的位置，位移取相邻两行原来的 top 差（含行间距，行高不等也准）。
     * 落点上方那几行往上挪、下方那几行往下挪，中间空出被拖那一行的高度。
     */
    private static void makeGap(List container, int from, int target, View dragged) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == dragged) continue;
            float shift = 0;
            if (target > from && i > from && i <= target) shift = container.getChildAt(i - 1).getTop() - child.getTop();
            else if (target < from && i >= target && i < from) shift = container.getChildAt(i + 1).getTop() - child.getTop();
            child.setTranslationY(shift);
        }
    }

    private static void finish(List container, View item, Drawable resting, boolean dragged) {
        item.setTranslationY(0);
        if (dragged) item.setBackground(resting);
        for (int i = 0; i < container.getChildCount(); i++) container.getChildAt(i).setTranslationY(0);
        container.front(null);
    }

    /** 拿起来时的底：白底黑框，圆角与列表行一致。 */
    private static Drawable inkLift(View view) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(0xFFFFFFFF);
        shape.setStroke(Math.max(2, Math.round(1.5f * view.getResources().getDisplayMetrics().density)), 0xFF000000);
        shape.setCornerRadius(10 * view.getResources().getDisplayMetrics().density);
        return shape;
    }

    /** 纵向容器，额外记住「拖动中的那一行」，把它排到最后画。 */
    private static final class List extends LinearLayout {
        private View front;

        List(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setChildrenDrawingOrderEnabled(true);
        }

        void front(View child) {
            front = child;
            invalidate();
        }

        @Override
        protected int getChildDrawingOrder(int count, int index) {
            int dragged = front == null ? -1 : indexOfChild(front);
            if (dragged < 0 || dragged >= count) return index;
            if (index == count - 1) return dragged;
            return index < dragged ? index : index + 1;
        }
    }

    /** 三条横线：宽 16dp、线粗 2dp、间距 5dp，颜色跟随正文墨色。 */
    private static final class Handle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float width, thickness, gap;

        Handle(Context context, Ink ink) {
            super(context);
            paint.setColor(Ink.INK);
            width = ink.dp(16);
            thickness = Math.max(2f, ink.dp(1.6f));
            gap = ink.dp(5);
            setLayoutParams(new ViewGroup.LayoutParams(ink.dp(34), ink.dp(44)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float left = cx - width / 2f, right = cx + width / 2f;
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * gap;
                canvas.drawRoundRect(left, y - thickness / 2f, right, y + thickness / 2f,
                        thickness / 2f, thickness / 2f, paint);
            }
        }
    }
}
