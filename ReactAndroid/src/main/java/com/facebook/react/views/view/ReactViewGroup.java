/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.views.view;

import static com.facebook.react.common.ReactConstants.TAG;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewStructure;
import android.view.animation.Animation;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.common.logging.FLog;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactNoCrashSoftException;
import com.facebook.react.bridge.ReactSoftExceptionLogger;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.config.ReactFeatureFlags;
import com.facebook.react.modules.i18nmanager.I18nUtil;
import com.facebook.react.touch.OnInterceptTouchEventListener;
import com.facebook.react.touch.ReactHitSlopView;
import com.facebook.react.touch.ReactInterceptingViewGroup;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.facebook.react.uimanager.MeasureSpecAssertions;
import com.facebook.react.uimanager.PointerEvents;
import com.facebook.react.uimanager.ReactClippingProhibitedView;
import com.facebook.react.uimanager.ReactClippingViewGroup;
import com.facebook.react.uimanager.ReactClippingViewGroupHelper;
import com.facebook.react.uimanager.ReactOverflowViewWithInset;
import com.facebook.react.uimanager.ReactPointerEventsView;
import com.facebook.react.uimanager.ReactZIndexedViewGroup;
import com.facebook.react.uimanager.RootView;
import com.facebook.react.uimanager.RootViewUtil;
import com.facebook.react.uimanager.ViewGroupDrawingOrderHelper;
import com.facebook.react.uimanager.ViewProps;
import com.facebook.react.uimanager.common.UIManagerType;
import com.facebook.react.uimanager.common.ViewUtil;
import com.facebook.yoga.YogaConstants;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Backing for a React View. Has support for borders, but since borders aren't common, lazy
 * initializes most of the storage needed for them.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class ReactViewGroup extends ViewGroup
  implements ReactInterceptingViewGroup,
  ReactClippingViewGroup,
  ReactPointerEventsView,
  ReactHitSlopView,
  ReactZIndexedViewGroup,
  ReactOverflowViewWithInset {

  private static final int ARRAY_CAPACITY_INCREMENT = 12;
  private static final int DEFAULT_BACKGROUND_COLOR = Color.TRANSPARENT;
  private static final LayoutParams sDefaultLayoutParam = new ViewGroup.LayoutParams(0, 0);
  private final Rect mOverflowInset = new Rect();
  /* should only be used in {@link #updateClippingToRect} */
  private static final Rect sHelperRect = new Rect();
  private @NonNull int[] focusDestinations = new int[0];
  private boolean autoFocus = false;
  private WeakReference<View> lastFocusedElement;
  private boolean mRecoverFocus = false;

  /**
   * This listener will be set for child views when removeClippedSubview property is enabled. When
   * children layout is updated, it will call {@link #updateSubviewClipStatus} to notify parent view
   * about that fact so that view can be attached/detached if necessary.
   *
   * <p>TODO(7728005): Attach/detach views in batch - once per frame in case when multiple children
   * update their layout.
   */
  private static final class ChildrenLayoutChangeListener implements View.OnLayoutChangeListener {

    private final ReactViewGroup mParent;

    private ChildrenLayoutChangeListener(ReactViewGroup parent) {
      mParent = parent;
    }

    @Override
    public void onLayoutChange(
      View v,
      int left,
      int top,
      int right,
      int bottom,
      int oldLeft,
      int oldTop,
      int oldRight,
      int oldBottom) {
      if (mParent.getRemoveClippedSubviews()) {
        mParent.updateSubviewClipStatus(v);
      }
    }
  }

  // Following properties are here to support the option {@code removeClippedSubviews}. This is a
  // temporary optimization/hack that is mainly applicable to the large list of images. The way
  // it's implemented is that we store an additional array of children in view node. We selectively
  // remove some of the views (detach) from it while still storing them in that additional array.
  // We override all possible add methods for {@link ViewGroup} so that we can control this process
  // whenever the option is set. We also override {@link ViewGroup#getChildAt} and
  // {@link ViewGroup#getChildCount} so those methods may return views that are not attached.
  // This is risky but allows us to perform a correct cleanup in {@link NativeViewHierarchyManager}.
  private boolean mRemoveClippedSubviews = false;
  private @Nullable
  View[] mAllChildren = null;
  private int mAllChildrenCount;
  private @Nullable
  Rect mClippingRect;
  private @Nullable
  Rect mHitSlopRect;
  private @Nullable
  String mOverflow;
  private PointerEvents mPointerEvents = PointerEvents.AUTO;
  private @Nullable
  ChildrenLayoutChangeListener mChildrenLayoutChangeListener;
  private @Nullable
  ReactViewBackgroundDrawable mReactBackgroundDrawable;
  private @Nullable
  OnInterceptTouchEventListener mOnInterceptTouchEventListener;
  private boolean mNeedsOffscreenAlphaCompositing = false;
  private @Nullable
  ViewGroupDrawingOrderHelper mDrawingOrderHelper = null;
  private @Nullable
  Path mPath;
  private int mLayoutDirection;
  private float mBackfaceOpacity = 1.f;
  private String mBackfaceVisibility = "visible";

  public ReactViewGroup(Context context) {
    super(context);
    setClipChildren(false);
  }

  private ViewGroupDrawingOrderHelper getDrawingOrderHelper() {
    if (mDrawingOrderHelper == null) {
      mDrawingOrderHelper = new ViewGroupDrawingOrderHelper(this);
    }
    return mDrawingOrderHelper;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    MeasureSpecAssertions.assertExplicitMeasureSpec(widthMeasureSpec, heightMeasureSpec);

    setMeasuredDimension(
      MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    // No-op since UIManagerModule handles actually laying out children.
  }

  @Override
  public void onRtlPropertiesChanged(int layoutDirection) {
    if (mReactBackgroundDrawable != null) {
      mReactBackgroundDrawable.setResolvedLayoutDirection(mLayoutDirection);
    }
  }

  @Override
  @SuppressLint("MissingSuperCall")
  public void requestLayout() {
    // No-op, terminate `requestLayout` here, UIManagerModule handles laying out children and
    // `layout` is called on all RN-managed views by `NativeViewHierarchyManager`
  }

  @TargetApi(23)
  @Override
  public void dispatchProvideStructure(ViewStructure structure) {
    try {
      super.dispatchProvideStructure(structure);
    } catch (NullPointerException e) {
      FLog.e(TAG, "NullPointerException when executing dispatchProvideStructure", e);
    }
  }

  @Override
  public void setBackgroundColor(int color) {
    if (color == Color.TRANSPARENT && mReactBackgroundDrawable == null) {
      // don't do anything, no need to allocate ReactBackgroundDrawable for transparent background
    } else {
      getOrCreateReactViewBackground().setColor(color);
    }
  }

  @Override
  public void setBackground(Drawable drawable) {
    throw new UnsupportedOperationException(
      "This method is not supported for ReactViewGroup instances");
  }

  public void setTranslucentBackgroundDrawable(@Nullable Drawable background) {
    // it's required to call setBackground to null, as in some of the cases we may set new
    // background to be a layer drawable that contains a drawable that has been setup
    // as a background previously. This will not work correctly as the drawable callback logic is
    // messed up in AOSP
    updateBackgroundDrawable(null);
    if (mReactBackgroundDrawable != null && background != null) {
      LayerDrawable layerDrawable =
        new LayerDrawable(new Drawable[]{mReactBackgroundDrawable, background});
      updateBackgroundDrawable(layerDrawable);
    } else if (background != null) {
      updateBackgroundDrawable(background);
    }
  }

  @Override
  public void setOnInterceptTouchEventListener(OnInterceptTouchEventListener listener) {
    mOnInterceptTouchEventListener = listener;
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (mOnInterceptTouchEventListener != null
      && mOnInterceptTouchEventListener.onInterceptTouchEvent(this, ev)) {
      return true;
    }
    // We intercept the touch event if the children are not supposed to receive it.
    if (mPointerEvents == PointerEvents.NONE || mPointerEvents == PointerEvents.BOX_ONLY) {
      return true;
    }
    return super.onInterceptTouchEvent(ev);
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    // We do not accept the touch event if this view is not supposed to receive it.
    if (mPointerEvents == PointerEvents.NONE || mPointerEvents == PointerEvents.BOX_NONE) {
      return false;
    }
    // The root view always assumes any view that was tapped wants the touch
    // and sends the event to JS as such.
    // We don't need to do bubbling in native (it's already happening in JS).
    // For an explanation of bubbling and capturing, see
    // http://javascript.info/tutorial/bubbling-and-capturing#capturing
    return true;
  }

  /**
   * We override this to allow developers to determine whether they need offscreen alpha compositing
   * or not. See the documentation of needsOffscreenAlphaCompositing in View.js.
   */
  @Override
  public boolean hasOverlappingRendering() {
    return mNeedsOffscreenAlphaCompositing;
  }

  /**
   * See the documentation of needsOffscreenAlphaCompositing in View.js.
   */
  public void setNeedsOffscreenAlphaCompositing(boolean needsOffscreenAlphaCompositing) {
    mNeedsOffscreenAlphaCompositing = needsOffscreenAlphaCompositing;
  }

  public void setBorderWidth(int position, float width) {
    getOrCreateReactViewBackground().setBorderWidth(position, width);
  }

  public void setBorderColor(int position, float rgb, float alpha) {
    getOrCreateReactViewBackground().setBorderColor(position, rgb, alpha);
  }

  public void setBorderRadius(float borderRadius) {
    ReactViewBackgroundDrawable backgroundDrawable = getOrCreateReactViewBackground();
    backgroundDrawable.setRadius(borderRadius);
  }

  public void setBorderRadius(float borderRadius, int position) {
    ReactViewBackgroundDrawable backgroundDrawable = getOrCreateReactViewBackground();
    backgroundDrawable.setRadius(borderRadius, position);
  }

  public void setBorderStyle(@Nullable String style) {
    getOrCreateReactViewBackground().setBorderStyle(style);
  }

  @Override
  public void setRemoveClippedSubviews(boolean removeClippedSubviews) {
    if (removeClippedSubviews == mRemoveClippedSubviews) {
      return;
    }
    mRemoveClippedSubviews = removeClippedSubviews;
    if (removeClippedSubviews) {
      mClippingRect = new Rect();
      ReactClippingViewGroupHelper.calculateClippingRect(this, mClippingRect);
      mAllChildrenCount = getChildCount();
      int initialSize = Math.max(12, mAllChildrenCount);
      mAllChildren = new View[initialSize];
      mChildrenLayoutChangeListener = new ChildrenLayoutChangeListener(this);
      for (int i = 0; i < mAllChildrenCount; i++) {
        View child = getChildAt(i);
        mAllChildren[i] = child;
        child.addOnLayoutChangeListener(mChildrenLayoutChangeListener);
      }
      updateClippingRect();
    } else {
      // Add all clipped views back, deallocate additional arrays, remove layoutChangeListener
      Assertions.assertNotNull(mClippingRect);
      Assertions.assertNotNull(mAllChildren);
      Assertions.assertNotNull(mChildrenLayoutChangeListener);
      for (int i = 0; i < mAllChildrenCount; i++) {
        mAllChildren[i].removeOnLayoutChangeListener(mChildrenLayoutChangeListener);
      }
      getDrawingRect(mClippingRect);
      updateClippingToRect(mClippingRect);
      mAllChildren = null;
      mClippingRect = null;
      mAllChildrenCount = 0;
      mChildrenLayoutChangeListener = null;
    }
  }

  @Override
  public boolean getRemoveClippedSubviews() {
    return mRemoveClippedSubviews;
  }

  @Override
  public void getClippingRect(Rect outClippingRect) {
    outClippingRect.set(mClippingRect);
  }

  @Override
  public void updateClippingRect() {
    if (!mRemoveClippedSubviews) {
      return;
    }

    Assertions.assertNotNull(mClippingRect);
    Assertions.assertNotNull(mAllChildren);

    ReactClippingViewGroupHelper.calculateClippingRect(this, mClippingRect);
    updateClippingToRect(mClippingRect);
  }

  private void updateClippingToRect(Rect clippingRect) {
    Assertions.assertNotNull(mAllChildren);
    int clippedSoFar = 0;
    for (int i = 0; i < mAllChildrenCount; i++) {
      updateSubviewClipStatus(clippingRect, i, clippedSoFar);
      if (mAllChildren[i].getParent() == null) {
        clippedSoFar++;
      }
    }
  }

  private void updateSubviewClipStatus(Rect clippingRect, int idx, int clippedSoFar) {
    UiThreadUtil.assertOnUiThread();

    View child = Assertions.assertNotNull(mAllChildren)[idx];
    sHelperRect.set(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
    boolean intersects =
      clippingRect.intersects(
        sHelperRect.left, sHelperRect.top, sHelperRect.right, sHelperRect.bottom);
    boolean needUpdateClippingRecursive = false;
    // We never want to clip children that are being animated, as this can easily break layout :
    // when layout animation changes size and/or position of views contained inside a listview that
    // clips offscreen children, we need to ensure that, when view exits the viewport, final size
    // and position is set prior to removing the view from its listview parent.
    // Otherwise, when view gets re-attached again, i.e when it re-enters the viewport after scroll,
    // it won't be size and located properly.
    Animation animation = child.getAnimation();
    boolean isAnimating = animation != null && !animation.hasEnded();
    if (!intersects && child.getParent() != null && !isAnimating) {
      // We can try saving on invalidate call here as the view that we remove is out of visible area
      // therefore invalidation is not necessary.
      super.removeViewsInLayout(idx - clippedSoFar, 1);
      needUpdateClippingRecursive = true;
    } else if (intersects && child.getParent() == null) {
      super.addViewInLayout(child, idx - clippedSoFar, sDefaultLayoutParam, true);
      invalidate();
      needUpdateClippingRecursive = true;
    } else if (intersects) {
      // If there is any intersection we need to inform the child to update its clipping rect
      needUpdateClippingRecursive = true;
    }
    if (needUpdateClippingRecursive) {
      if (child instanceof ReactClippingViewGroup) {
        // we don't use {@link sHelperRect} until the end of this loop, therefore it's safe
        // to call this method that may write to the same {@link sHelperRect} object.
        ReactClippingViewGroup clippingChild = (ReactClippingViewGroup) child;
        if (clippingChild.getRemoveClippedSubviews()) {
          clippingChild.updateClippingRect();
        }
      }
    }
  }

  private void updateSubviewClipStatus(View subview) {
    if (!mRemoveClippedSubviews || getParent() == null) {
      return;
    }

    Assertions.assertNotNull(mClippingRect);
    Assertions.assertNotNull(mAllChildren);

    // do fast check whether intersect state changed
    sHelperRect.set(subview.getLeft(), subview.getTop(), subview.getRight(), subview.getBottom());
    boolean intersects =
      mClippingRect.intersects(
        sHelperRect.left, sHelperRect.top, sHelperRect.right, sHelperRect.bottom);

    // If it was intersecting before, should be attached to the parent
    boolean oldIntersects = (subview.getParent() != null);

    if (intersects != oldIntersects) {
      int clippedSoFar = 0;
      for (int i = 0; i < mAllChildrenCount; i++) {
        if (mAllChildren[i] == subview) {
          updateSubviewClipStatus(mClippingRect, i, clippedSoFar);
          break;
        }
        if (mAllChildren[i].getParent() == null) {
          clippedSoFar++;
        }
      }
    }
  }

  boolean moveFocusToFirstFocusable(ReactViewGroup viewGroup) {
    ArrayList<View> focusables = new ArrayList<View>(0);
    /**
     * `addFocusables` is the method used by `FocusFinder` to determine
     * which elements are `focusable` within the given view.
     * Here we use it for the exact purpose. It mutates/populates the `focusables` array list.
     * Focus direction (FOCUS_DOWN) doesn't matter at all because
     * it's not being used by the underlying implementation.
     */
    viewGroup.addFocusables(focusables, FOCUS_DOWN, FOCUSABLES_ALL);
    /**
     * Depending on ViewGroup's `descendantFocusability` property,
     * the first element can be the ViewGroup itself.
     * The other ones on the list can be non-focusable as well.
     * So, we run a loop till finding the first real focusable element.
     */
    if (focusables.size() <= 0) return false;

    View firstFocusableElement = null;
    Integer index = 0;
    while (firstFocusableElement == null && index < focusables.size()) {
      View elem = focusables.get(index);
      if (elem.isFocusable() && elem != viewGroup) {
        firstFocusableElement = elem;
        break;
      }
      index++;
    }

    if (firstFocusableElement != null) return firstFocusableElement.requestFocus();

    return false;
  }

  void recoverFocus(View view) {
    if (!view.hasFocus() || !(view instanceof ReactViewGroup)) return;

    ReactViewGroup parentFocusGuide = findParentFocusGuide(view);
    if (parentFocusGuide == null) return;

    /**
     * Making `parentFocusGuide` focusable for a brief time to
     * temporarily move the focus to it. We do this to prevent
     * Android from moving the focus to top-left-most element of the screen.
     */
    parentFocusGuide.mRecoverFocus = true;
    parentFocusGuide.setFocusable(true);
    parentFocusGuide.requestFocus();

    /**
     * We set a Runnable to wait and make sure every layout related action gets completed
     * before trying to find a new focus candidate inside the `parentFocusGuide`.
     */
    UiThreadUtil.runOnUiThread(
      new Runnable() {
        @Override
        public void run() {
          moveFocusToFirstFocusable(parentFocusGuide);

          parentFocusGuide.setFocusable(false);
          parentFocusGuide.mRecoverFocus = false;
        }
      });
  }

  @Override
  public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
    return super.getChildVisibleRect(child, r, offset);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  private boolean customDrawOrderDisabled() {
    if (getId() == NO_ID) {
      return false;
    }
    return ViewUtil.getUIManagerType(getId()) == UIManagerType.FABRIC;
  }

  @Override
  public void addView(View child, int index, ViewGroup.LayoutParams params) {
    // This will get called for every overload of addView so there is not need to override every
    // method.

    if (!customDrawOrderDisabled()) {
      getDrawingOrderHelper().handleAddView(child);
      setChildrenDrawingOrderEnabled(getDrawingOrderHelper().shouldEnableCustomDrawingOrder());
    } else {
      setChildrenDrawingOrderEnabled(false);
    }

    super.addView(child, index, params);
  }

  @Override
  public void removeView(View view) {
    UiThreadUtil.assertOnUiThread();

    if (!customDrawOrderDisabled()) {
      getDrawingOrderHelper().handleRemoveView(view);
      setChildrenDrawingOrderEnabled(getDrawingOrderHelper().shouldEnableCustomDrawingOrder());
    } else {
      setChildrenDrawingOrderEnabled(false);
    }

    recoverFocus(view);

    super.removeView(view);
  }

  @Override
  public void removeViewAt(int index) {
    UiThreadUtil.assertOnUiThread();

    if (!customDrawOrderDisabled()) {
      getDrawingOrderHelper().handleRemoveView(getChildAt(index));
      setChildrenDrawingOrderEnabled(getDrawingOrderHelper().shouldEnableCustomDrawingOrder());
    } else {
      setChildrenDrawingOrderEnabled(false);
    }

    recoverFocus(getChildAt(index));

    super.removeViewAt(index);
  }

  @Override
  protected int getChildDrawingOrder(int childCount, int index) {
    UiThreadUtil.assertOnUiThread();

    if (!customDrawOrderDisabled()) {
      return getDrawingOrderHelper().getChildDrawingOrder(childCount, index);
    } else {
      return index;
    }
  }

  @Override
  public int getZIndexMappedChildIndex(int index) {
    UiThreadUtil.assertOnUiThread();

    if (!customDrawOrderDisabled() && getDrawingOrderHelper().shouldEnableCustomDrawingOrder()) {
      return getDrawingOrderHelper().getChildDrawingOrder(getChildCount(), index);
    }

    // Fabric behavior
    return index;
  }

  @Override
  public void updateDrawingOrder() {
    if (customDrawOrderDisabled()) {
      return;
    }

    getDrawingOrderHelper().update();
    setChildrenDrawingOrderEnabled(getDrawingOrderHelper().shouldEnableCustomDrawingOrder());
    invalidate();
  }

  @Override
  public PointerEvents getPointerEvents() {
    return mPointerEvents;
  }

  @Override
  protected void dispatchSetPressed(boolean pressed) {
    // Prevents the ViewGroup from dispatching the pressed state
    // to it's children.
  }

  /*package*/ void setPointerEvents(PointerEvents pointerEvents) {
    mPointerEvents = pointerEvents;
  }

  /*package*/ int getAllChildrenCount() {
    return mAllChildrenCount;
  }

  /*package*/ View getChildAtWithSubviewClippingEnabled(int index) {
    return Assertions.assertNotNull(mAllChildren)[index];
  }

  /*package*/ void addViewWithSubviewClippingEnabled(View child, int index) {
    addViewWithSubviewClippingEnabled(child, index, sDefaultLayoutParam);
  }

  /*package*/ void addViewWithSubviewClippingEnabled(
    final View child, int index, ViewGroup.LayoutParams params) {
    Assertions.assertCondition(mRemoveClippedSubviews);
    Assertions.assertNotNull(mClippingRect);
    Assertions.assertNotNull(mAllChildren);
    addInArray(child, index);
    // we add view as "clipped" and then run {@link #updateSubviewClipStatus} to conditionally
    // attach it
    int clippedSoFar = 0;
    for (int i = 0; i < index; i++) {
      if (mAllChildren[i].getParent() == null) {
        clippedSoFar++;
      }
    }
    updateSubviewClipStatus(mClippingRect, index, clippedSoFar);
    child.addOnLayoutChangeListener(mChildrenLayoutChangeListener);

    if (child instanceof ReactClippingProhibitedView) {
      UiThreadUtil.runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            if (!child.isShown()) {
              ReactSoftExceptionLogger.logSoftException(
                TAG,
                new ReactNoCrashSoftException(
                  "Child view has been added to Parent view in which it is clipped and not visible."
                    + " This is not legal for this particular child view. Child: ["
                    + child.getId()
                    + "] "
                    + child.toString()
                    + " Parent: ["
                    + getId()
                    + "] "
                    + toString()));
            }
          }
        });
    }
  }

  /*package*/ void removeViewWithSubviewClippingEnabled(View view) {
    UiThreadUtil.assertOnUiThread();

    Assertions.assertCondition(mRemoveClippedSubviews);
    Assertions.assertNotNull(mClippingRect);
    Assertions.assertNotNull(mAllChildren);
    view.removeOnLayoutChangeListener(mChildrenLayoutChangeListener);
    int index = indexOfChildInAllChildren(view);
    if (mAllChildren[index].getParent() != null) {
      int clippedSoFar = 0;
      for (int i = 0; i < index; i++) {
        if (mAllChildren[i].getParent() == null) {
          clippedSoFar++;
        }
      }
      super.removeViewsInLayout(index - clippedSoFar, 1);
    }
    removeFromArray(index);
  }

  /*package*/ void removeAllViewsWithSubviewClippingEnabled() {
    Assertions.assertCondition(mRemoveClippedSubviews);
    Assertions.assertNotNull(mAllChildren);
    for (int i = 0; i < mAllChildrenCount; i++) {
      mAllChildren[i].removeOnLayoutChangeListener(mChildrenLayoutChangeListener);
    }
    removeAllViewsInLayout();
    mAllChildrenCount = 0;
  }

  private int indexOfChildInAllChildren(View child) {
    final int count = mAllChildrenCount;
    final View[] children = Assertions.assertNotNull(mAllChildren);
    for (int i = 0; i < count; i++) {
      if (children[i] == child) {
        return i;
      }
    }
    return -1;
  }

  private void addInArray(View child, int index) {
    View[] children = Assertions.assertNotNull(mAllChildren);
    final int count = mAllChildrenCount;
    final int size = children.length;
    if (index == count) {
      if (size == count) {
        mAllChildren = new View[size + ARRAY_CAPACITY_INCREMENT];
        System.arraycopy(children, 0, mAllChildren, 0, size);
        children = mAllChildren;
      }
      children[mAllChildrenCount++] = child;
    } else if (index < count) {
      if (size == count) {
        mAllChildren = new View[size + ARRAY_CAPACITY_INCREMENT];
        System.arraycopy(children, 0, mAllChildren, 0, index);
        System.arraycopy(children, index, mAllChildren, index + 1, count - index);
        children = mAllChildren;
      } else {
        System.arraycopy(children, index, children, index + 1, count - index);
      }
      children[index] = child;
      mAllChildrenCount++;
    } else {
      throw new IndexOutOfBoundsException("index=" + index + " count=" + count);
    }
  }

  // This method also sets the child's mParent to null
  private void removeFromArray(int index) {
    final View[] children = Assertions.assertNotNull(mAllChildren);
    final int count = mAllChildrenCount;
    if (index == count - 1) {
      children[--mAllChildrenCount] = null;
    } else if (index >= 0 && index < count) {
      System.arraycopy(children, index + 1, children, index, count - index - 1);
      children[--mAllChildrenCount] = null;
    } else {
      throw new IndexOutOfBoundsException();
    }
  }

  @VisibleForTesting
  public int getBackgroundColor() {
    if (getBackground() != null) {
      return ((ReactViewBackgroundDrawable) getBackground()).getColor();
    }
    return DEFAULT_BACKGROUND_COLOR;
  }

  private ReactViewBackgroundDrawable getOrCreateReactViewBackground() {
    if (mReactBackgroundDrawable == null) {
      mReactBackgroundDrawable = new ReactViewBackgroundDrawable(getContext());
      Drawable backgroundDrawable = getBackground();
      updateBackgroundDrawable(
        null); // required so that drawable callback is cleared before we add the
      // drawable back as a part of LayerDrawable
      if (backgroundDrawable == null) {
        updateBackgroundDrawable(mReactBackgroundDrawable);
      } else {
        LayerDrawable layerDrawable =
          new LayerDrawable(new Drawable[]{mReactBackgroundDrawable, backgroundDrawable});
        updateBackgroundDrawable(layerDrawable);
      }

      mLayoutDirection =
        I18nUtil.getInstance().isRTL(getContext()) ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR;
      mReactBackgroundDrawable.setResolvedLayoutDirection(mLayoutDirection);
    }
    return mReactBackgroundDrawable;
  }

  @Override
  public @Nullable
  Rect getHitSlopRect() {
    return mHitSlopRect;
  }

  public void setHitSlopRect(@Nullable Rect rect) {
    mHitSlopRect = rect;
  }

  public void setOverflow(String overflow) {
    mOverflow = overflow;
    invalidate();
  }

  @Override
  public @Nullable
  String getOverflow() {
    return mOverflow;
  }

  @Override
  public void setOverflowInset(int left, int top, int right, int bottom) {
    mOverflowInset.set(left, top, right, bottom);
  }

  @Override
  public Rect getOverflowInset() {
    return mOverflowInset;
  }

  /**
   * Set the background for the view or remove the background. It calls {@link
   * #setBackground(Drawable)} or {@link #setBackgroundDrawable(Drawable)} based on the sdk version.
   *
   * @param drawable {@link Drawable} The Drawable to use as the background, or null to remove the
   *                 background
   */
  private void updateBackgroundDrawable(Drawable drawable) {
    super.setBackground(drawable);
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    try {
      dispatchOverflowDraw(canvas);
      super.dispatchDraw(canvas);
    } catch (NullPointerException | StackOverflowError e) {
      // Adding special exception management for StackOverflowError for logging purposes.
      // This will be removed in the future.
      RootView rootView = RootViewUtil.getRootView(ReactViewGroup.this);
      if (rootView != null) {
        rootView.handleException(e);
      } else {
        if (getContext() instanceof ReactContext) {
          ReactContext reactContext = (ReactContext) getContext();
          reactContext.handleException(
            new IllegalViewOperationException("StackOverflowException", this, e));
        } else {
          throw e;
        }
      }
    }
  }

  @Override
  protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
    boolean drawWithZ =
      child.getElevation() > 0 && ReactFeatureFlags.insertZReorderBarriersOnViewGroupChildren;

    if (drawWithZ) {
      CanvasUtil.enableZ(canvas, true);
    }

    boolean result = super.drawChild(canvas, child, drawingTime);

    if (drawWithZ) {
      CanvasUtil.enableZ(canvas, false);
    }
    return result;
  }

  private void dispatchOverflowDraw(Canvas canvas) {
    if (mOverflow != null) {
      switch (mOverflow) {
        case ViewProps.VISIBLE:
          if (mPath != null) {
            mPath.rewind();
          }
          break;
        case ViewProps.HIDDEN:
        case ViewProps.SCROLL:
          float left = 0f;
          float top = 0f;
          float right = getWidth();
          float bottom = getHeight();

          boolean hasClipPath = false;

          if (mReactBackgroundDrawable != null) {
            final RectF borderWidth = mReactBackgroundDrawable.getDirectionAwareBorderInsets();

            if (borderWidth.top > 0
              || borderWidth.left > 0
              || borderWidth.bottom > 0
              || borderWidth.right > 0) {
              left += borderWidth.left;
              top += borderWidth.top;
              right -= borderWidth.right;
              bottom -= borderWidth.bottom;
            }

            final float borderRadius = mReactBackgroundDrawable.getFullBorderRadius();
            float topLeftBorderRadius =
              mReactBackgroundDrawable.getBorderRadiusOrDefaultTo(
                borderRadius, ReactViewBackgroundDrawable.BorderRadiusLocation.TOP_LEFT);
            float topRightBorderRadius =
              mReactBackgroundDrawable.getBorderRadiusOrDefaultTo(
                borderRadius, ReactViewBackgroundDrawable.BorderRadiusLocation.TOP_RIGHT);
            float bottomLeftBorderRadius =
              mReactBackgroundDrawable.getBorderRadiusOrDefaultTo(
                borderRadius, ReactViewBackgroundDrawable.BorderRadiusLocation.BOTTOM_LEFT);
            float bottomRightBorderRadius =
              mReactBackgroundDrawable.getBorderRadiusOrDefaultTo(
                borderRadius, ReactViewBackgroundDrawable.BorderRadiusLocation.BOTTOM_RIGHT);

            final boolean isRTL = mLayoutDirection == View.LAYOUT_DIRECTION_RTL;
            float topStartBorderRadius =
              mReactBackgroundDrawable.getBorderRadius(
                ReactViewBackgroundDrawable.BorderRadiusLocation.TOP_START);
            float topEndBorderRadius =
              mReactBackgroundDrawable.getBorderRadius(
                ReactViewBackgroundDrawable.BorderRadiusLocation.TOP_END);
            float bottomStartBorderRadius =
              mReactBackgroundDrawable.getBorderRadius(
                ReactViewBackgroundDrawable.BorderRadiusLocation.BOTTOM_START);
            float bottomEndBorderRadius =
              mReactBackgroundDrawable.getBorderRadius(
                ReactViewBackgroundDrawable.BorderRadiusLocation.BOTTOM_END);

            if (I18nUtil.getInstance().doLeftAndRightSwapInRTL(getContext())) {
              if (YogaConstants.isUndefined(topStartBorderRadius)) {
                topStartBorderRadius = topLeftBorderRadius;
              }

              if (YogaConstants.isUndefined(topEndBorderRadius)) {
                topEndBorderRadius = topRightBorderRadius;
              }

              if (YogaConstants.isUndefined(bottomStartBorderRadius)) {
                bottomStartBorderRadius = bottomLeftBorderRadius;
              }

              if (YogaConstants.isUndefined(bottomEndBorderRadius)) {
                bottomEndBorderRadius = bottomRightBorderRadius;
              }

              final float directionAwareTopLeftRadius =
                isRTL ? topEndBorderRadius : topStartBorderRadius;
              final float directionAwareTopRightRadius =
                isRTL ? topStartBorderRadius : topEndBorderRadius;
              final float directionAwareBottomLeftRadius =
                isRTL ? bottomEndBorderRadius : bottomStartBorderRadius;
              final float directionAwareBottomRightRadius =
                isRTL ? bottomStartBorderRadius : bottomEndBorderRadius;

              topLeftBorderRadius = directionAwareTopLeftRadius;
              topRightBorderRadius = directionAwareTopRightRadius;
              bottomLeftBorderRadius = directionAwareBottomLeftRadius;
              bottomRightBorderRadius = directionAwareBottomRightRadius;
            } else {
              final float directionAwareTopLeftRadius =
                isRTL ? topEndBorderRadius : topStartBorderRadius;
              final float directionAwareTopRightRadius =
                isRTL ? topStartBorderRadius : topEndBorderRadius;
              final float directionAwareBottomLeftRadius =
                isRTL ? bottomEndBorderRadius : bottomStartBorderRadius;
              final float directionAwareBottomRightRadius =
                isRTL ? bottomStartBorderRadius : bottomEndBorderRadius;

              if (!YogaConstants.isUndefined(directionAwareTopLeftRadius)) {
                topLeftBorderRadius = directionAwareTopLeftRadius;
              }

              if (!YogaConstants.isUndefined(directionAwareTopRightRadius)) {
                topRightBorderRadius = directionAwareTopRightRadius;
              }

              if (!YogaConstants.isUndefined(directionAwareBottomLeftRadius)) {
                bottomLeftBorderRadius = directionAwareBottomLeftRadius;
              }

              if (!YogaConstants.isUndefined(directionAwareBottomRightRadius)) {
                bottomRightBorderRadius = directionAwareBottomRightRadius;
              }
            }

            if (topLeftBorderRadius > 0
              || topRightBorderRadius > 0
              || bottomRightBorderRadius > 0
              || bottomLeftBorderRadius > 0) {
              if (mPath == null) {
                mPath = new Path();
              }

              mPath.rewind();
              mPath.addRoundRect(
                new RectF(left, top, right, bottom),
                new float[]{
                  Math.max(topLeftBorderRadius - borderWidth.left, 0),
                  Math.max(topLeftBorderRadius - borderWidth.top, 0),
                  Math.max(topRightBorderRadius - borderWidth.right, 0),
                  Math.max(topRightBorderRadius - borderWidth.top, 0),
                  Math.max(bottomRightBorderRadius - borderWidth.right, 0),
                  Math.max(bottomRightBorderRadius - borderWidth.bottom, 0),
                  Math.max(bottomLeftBorderRadius - borderWidth.left, 0),
                  Math.max(bottomLeftBorderRadius - borderWidth.bottom, 0),
                },
                Path.Direction.CW);
              canvas.clipPath(mPath);
              hasClipPath = true;
            }
          }

          if (!hasClipPath) {
            canvas.clipRect(new RectF(left, top, right, bottom));
          }
          break;
        default:
          break;
      }
    }
  }

  public void setOpacityIfPossible(float opacity) {
    mBackfaceOpacity = opacity;
    setBackfaceVisibilityDependantOpacity();
  }

  public void setBackfaceVisibility(String backfaceVisibility) {
    mBackfaceVisibility = backfaceVisibility;
    setBackfaceVisibilityDependantOpacity();
  }

  public void setBackfaceVisibilityDependantOpacity() {
    boolean isBackfaceVisible = mBackfaceVisibility.equals("visible");

    if (isBackfaceVisible) {
      setAlpha(mBackfaceOpacity);
      return;
    }

    float rotationX = getRotationX();
    float rotationY = getRotationY();

    boolean isFrontfaceVisible =
      (rotationX >= -90.f && rotationX < 90.f) && (rotationY >= -90.f && rotationY < 90.f);

    if (isFrontfaceVisible) {
      setAlpha(mBackfaceOpacity);
      return;
    }

    setAlpha(0);
  }

  private View findDestinationView() {
    for (int focusDestination : focusDestinations) {
      View childViewWithTag = findViewById(focusDestination);
      if (childViewWithTag != null) return childViewWithTag;
      View viewWithTag = getRootView().findViewById(focusDestination);
      if (viewWithTag != null) return viewWithTag;
    }
    return null;
  }

  private static boolean requestFocusViewOrAncestor(View destination) {
    View v = destination;
    while (v != null) {
      if (v.requestFocus()) {
        return true;
      }
      ViewParent parent = v.getParent();
      if (parent instanceof View) {
        v = (View) parent;
      } else {
        v = null;
      }
    }
    return false;
  }

  private boolean isTVFocusGuide() {
    return focusDestinations.length > 0 || autoFocus;
  }

  @Nullable
  private ReactViewGroup findParentFocusGuide(View view) {
    ViewParent parent = view.getParent();

    while (parent != null) {
      if (parent instanceof ReactViewGroup) {
        ReactViewGroup elem = (ReactViewGroup) parent;
        if (elem.isTVFocusGuide()) return elem;
      }
      parent = parent.getParent();
    }

    return null;
  }

  /***
   * This is meant to be used only for TVFocusGuide.
   * @return View | null
   */
  @Nullable
  private View getFocusedChildOfFocusGuide() {
    if (!isTVFocusGuide()) return null;

    /*
     * We can have nested `TVFocusGuide`s, this is a typical scenario.
     * The problem is, returned element from `getFocusedChild` can be
     * either a direct `child` of the `TVFocusGuide` or a `descendant` of it.
     * Let's say if we run `getFocusedChild` for the Root element, it will always
     * give us an element even though the Root element is not the direct parent
     * of the focused element.
     * So, we need to find the closest `TVFocusGuide` to the focused child
     * to make sure the focused element's closest `TVFocusGuide` is "this" one.
     */
    View focusedChild = this.getFocusedChild();
    if (focusedChild == null) return null;

    ReactViewGroup parentFocusGuide = findParentFocusGuide(focusedChild);
    if (parentFocusGuide == this) return focusedChild;

    return null;
  }

  @Override
  public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
    /**
     * TVFocusGuides should reveral their children when `mRecoverFocus` is set.
     * `mRecoverFocus` flag indicates a temporary focus recovery mode it's in which
     * requires full access to children focusable elements.
     */
    if (isTVFocusGuide() && !mRecoverFocus) {
      View focusedChild = getFocusedChildOfFocusGuide();

      /*
       * We only include the view's (TVFocusGuide) itself if it doesn't have a child that currently has the focus.
       * Otherwise, it means focus is already in the `TVFocusGuide` and all of it's descendants should be included
       * for focus calculations.
       */
      if (focusedChild == null) {
        views.add(this);
        return;
      }
    }

    super.addFocusables(views, direction, focusableMode);
  }

  @Override
  public void requestChildFocus(View child, View focused) {
    super.requestChildFocus(child, focused);

    if (autoFocus) {
      lastFocusedElement = new WeakReference<View>(focused);
    }
  }

  @Override
  protected void onFocusChanged(boolean gainFocus, int direction, @Nullable Rect previouslyFocusedRect) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
  }

  @Override
  public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
    if (!isTVFocusGuide() || mRecoverFocus) {
      return super.requestFocus(direction, previouslyFocusedRect);
    }

    if (this.autoFocus) {
      View lastFocusedElem = lastFocusedElement.get();

      if (lastFocusedElem != null) {

        if (lastFocusedElem.isAttachedToWindow()) {
          lastFocusedElem.requestFocus();
          return true;
        }

        /**
         * `lastFocusedElem` can get detached based on application logic.
         * If the code reaches here, that means we're dealing with that case.
         * We should set `lastFocusedElem` to null and let the focus determination
         * logic below to do its magic and redirect focus to the first element.
         */
        lastFocusedElement = new WeakReference<View>(null);
      }

      // Try moving the focus to the first focusable element otherwise.
      if (moveFocusToFirstFocusable(this)) {
        return true;
      }
    }

    View destination = findDestinationView();

    if (destination != null && requestFocusViewOrAncestor(destination)) {
      return true;
    }

    for (int i = 0; i < getChildCount(); i++) {
      if (getChildAt(i).requestFocus()) {
        return true;
      }
    }

    return false;
  }

  public void setFocusDestinations(@NonNull int[] focusDestinations) {
    this.focusDestinations = focusDestinations;
  }

  public void setAutoFocusTV(boolean autoFocus) {
    this.autoFocus = autoFocus;
    lastFocusedElement = new WeakReference<View>(null);
  }
}
