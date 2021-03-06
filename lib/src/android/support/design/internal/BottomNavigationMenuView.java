/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.design.internal;

import static android.support.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.design.R;
import android.support.design.widget.BottomNavigationView;
import android.support.design.widget.BottomNavigationView.ShiftingMode;
import android.support.transition.AutoTransition;
import android.support.transition.TransitionManager;
import android.support.transition.TransitionSet;
import android.support.v4.util.Pools;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.view.menu.MenuItemImpl;
import android.support.v7.view.menu.MenuView;
import android.util.AttributeSet;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

/** @hide For internal use only. */
@RestrictTo(LIBRARY_GROUP)
public class BottomNavigationMenuView extends ViewGroup implements MenuView {
  private static final long ACTIVE_ANIMATION_DURATION_MS = 115L;

  private final TransitionSet mSet;
  private final int mInactiveItemMaxWidth;
  private final int mInactiveItemMinWidth;
  private final int mActiveItemMaxWidth;
  private final int mActiveItemMinWidth;
  private final int mItemHeight;
  private final OnClickListener mOnClickListener;
  private final Pools.Pool<BottomNavigationItemView> mItemPool = new Pools.SynchronizedPool<>(5);

  private int mShiftingModeFlag = BottomNavigationView.SHIFTING_MODE_AUTO;

  private BottomNavigationItemView[] mButtons;
  private int mSelectedItemId = 0;
  private int mSelectedItemPosition = 0;
  private ColorStateList mItemIconTint;
  private ColorStateList mItemTextColor;
  private int mItemBackgroundRes;
  private int[] mTempChildWidths;

  private BottomNavigationPresenter mPresenter;
  private MenuBuilder mMenu;

  public BottomNavigationMenuView(Context context) {
    this(context, null);
  }

  public BottomNavigationMenuView(Context context, AttributeSet attrs) {
    super(context, attrs);
    final Resources res = getResources();
    mInactiveItemMaxWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_item_max_width);
    mInactiveItemMinWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_item_min_width);
    mActiveItemMaxWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_active_item_max_width);
    mActiveItemMinWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_active_item_min_width);
    mItemHeight = res.getDimensionPixelSize(R.dimen.design_bottom_navigation_height);

    mSet = new AutoTransition();
    mSet.setOrdering(TransitionSet.ORDERING_TOGETHER);
    mSet.setDuration(ACTIVE_ANIMATION_DURATION_MS);
    mSet.setInterpolator(new FastOutSlowInInterpolator());
    mSet.addTransition(new TextScale());

    mOnClickListener =
        new OnClickListener() {
          @Override
          public void onClick(View v) {
            final BottomNavigationItemView itemView = (BottomNavigationItemView) v;
            MenuItem item = itemView.getItemData();
            if (!mMenu.performItemAction(item, mPresenter, 0)) {
              item.setChecked(true);
            }
          }
        };
    mTempChildWidths = new int[BottomNavigationMenu.MAX_ITEM_COUNT];
  }

  @Override
  public void initialize(MenuBuilder menu) {
    mMenu = menu;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int width = MeasureSpec.getSize(widthMeasureSpec);
    // Use visible item count to calculate widths
    final int visibleCount = mMenu.getVisibleItems().size();
    // Use total item counts to measure children
    final int totalCount = getChildCount();

    final int heightSpec = MeasureSpec.makeMeasureSpec(mItemHeight, MeasureSpec.EXACTLY);

    if (isShifting(mShiftingModeFlag, visibleCount)) {
      final View activeChild = getChildAt(mSelectedItemPosition);
      int activeItemWidth = mActiveItemMinWidth;
      if (activeChild.getVisibility() != View.GONE) {
        // Do an AT_MOST measure pass on the active child to get its desired width, and resize the
        // active child view based on that width
        activeChild.measure(
            MeasureSpec.makeMeasureSpec(mActiveItemMaxWidth, MeasureSpec.AT_MOST), heightSpec);
        activeItemWidth = Math.max(activeItemWidth, activeChild.getMeasuredWidth());
      }
      final int inactiveCount = visibleCount - (activeChild.getVisibility() != View.GONE ? 1 : 0);
      final int activeMaxAvailable = width - inactiveCount * mInactiveItemMinWidth;
      final int activeWidth =
          Math.min(activeMaxAvailable, Math.min(activeItemWidth, mActiveItemMaxWidth));
      final int inactiveMaxAvailable =
          (width - activeWidth) / (inactiveCount == 0 ? 1 : inactiveCount);
      final int inactiveWidth = Math.min(inactiveMaxAvailable, mInactiveItemMaxWidth);
      int extra = width - activeWidth - inactiveWidth * inactiveCount;
      for (int i = 0; i < totalCount; i++) {
        if (getChildAt(i).getVisibility() != View.GONE) {
          mTempChildWidths[i] = (i == mSelectedItemPosition) ? activeWidth : inactiveWidth;
          // Account for integer division which sometimes leaves some extra pixel spaces.
          // e.g. If the nav was 10px wide, and 3 children were measured to be 3px-3px-3px, there
          // would be a 1px gap somewhere, which this fills in.
          if (extra > 0) {
            mTempChildWidths[i]++;
            extra--;
          }
        } else {
          mTempChildWidths[i] = 0;
        }
      }
    } else {
      final int maxAvailable = width / (visibleCount == 0 ? 1 : visibleCount);
      final int childWidth = Math.min(maxAvailable, mActiveItemMaxWidth);
      int extra = width - childWidth * visibleCount;
      for (int i = 0; i < totalCount; i++) {
        if (getChildAt(i).getVisibility() != View.GONE) {
          mTempChildWidths[i] = childWidth;
          if (extra > 0) {
            mTempChildWidths[i]++;
            extra--;
          }
        } else {
          mTempChildWidths[i] = 0;
        }
      }
    }

    int totalWidth = 0;
    for (int i = 0; i < totalCount; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == GONE) {
        continue;
      }
      child.measure(
          MeasureSpec.makeMeasureSpec(mTempChildWidths[i], MeasureSpec.EXACTLY), heightSpec);
      ViewGroup.LayoutParams params = child.getLayoutParams();
      params.width = child.getMeasuredWidth();
      totalWidth += child.getMeasuredWidth();
    }
    setMeasuredDimension(
        View.resolveSizeAndState(
            totalWidth, MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY), 0),
        View.resolveSizeAndState(mItemHeight, heightSpec, 0));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    final int count = getChildCount();
    final int width = right - left;
    final int height = bottom - top;
    int used = 0;
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == GONE) {
        continue;
      }
      if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL) {
        child.layout(width - used - child.getMeasuredWidth(), 0, width - used, height);
      } else {
        child.layout(used, 0, child.getMeasuredWidth() + used, height);
      }
      used += child.getMeasuredWidth();
    }
  }

  @Override
  public int getWindowAnimations() {
    return 0;
  }

  /**
   * Sets the tint which is applied to the menu items' icons.
   *
   * @param tint the tint to apply
   */
  public void setIconTintList(ColorStateList tint) {
    mItemIconTint = tint;
    if (mButtons == null) return;
    for (BottomNavigationItemView item : mButtons) {
      item.setIconTintList(tint);
    }
  }

  /**
   * Returns the tint which is applied to menu items' icons.
   *
   * @return the ColorStateList that is used to tint menu items' icons
   */
  @Nullable
  public ColorStateList getIconTintList() {
    return mItemIconTint;
  }

  /**
   * Sets the text color to be used on menu items.
   *
   * @param color the ColorStateList used for menu items' text.
   */
  public void setItemTextColor(ColorStateList color) {
    mItemTextColor = color;
    if (mButtons == null) return;
    for (BottomNavigationItemView item : mButtons) {
      item.setTextColor(color);
    }
  }

  /**
   * Returns the text color used on menu items.
   *
   * @return the ColorStateList used for menu items' text
   */
  public ColorStateList getItemTextColor() {
    return mItemTextColor;
  }

  /**
   * Sets the resource ID to be used for item background.
   *
   * @param background the resource ID of the background
   */
  public void setItemBackgroundRes(int background) {
    mItemBackgroundRes = background;
    if (mButtons == null) return;
    for (BottomNavigationItemView item : mButtons) {
      item.setItemBackground(background);
    }
  }

  /**
   * Returns the resource ID for the background of the menu items.
   *
   * @return the resource ID for the background
   */
  public int getItemBackgroundRes() {
    return mItemBackgroundRes;
  }

  /**
   * Sets shifting mode override flag for menu view. If this flag is set to {@link
   * ShiftingMode#SHIFTING_MODE_OFF}, this menu will not have shifting behavior even if it has more
   * than 3 children. If this flag is set to {@link ShiftingMode#SHIFTING_MODE_ON}, this menu will
   * have shifting behavior for any number of children. If this flag is set to {@link
   * ShiftingMode#SHIFTING_MODE_AUTO} this menu will have shifting behavior only if it has 3 or more
   * children.
   *
   * @param shiftingMode one of {@link ShiftingMode#SHIFTING_MODE_OFF}, {@link
   *     ShiftingMode#SHIFTING_MODE_ON}, or {@link ShiftingMode#SHIFTING_MODE_AUTO}
   */
  public void setShiftingMode(@ShiftingMode int shiftingMode) {
    mShiftingModeFlag = shiftingMode;
  }

  /**
   * Gets the status of shifting mode override flag for this menu view.
   *
   * @return Shifting mode flag for this BottomNavigationView (default {@link
   *     ShiftingMode#SHIFTING_MODE_AUTO})
   */
  @ShiftingMode
  public int getShiftingMode() {
    return mShiftingModeFlag;
  }

  public void setPresenter(BottomNavigationPresenter presenter) {
    mPresenter = presenter;
  }

  public void buildMenuView() {
    removeAllViews();
    if (mButtons != null) {
      for (BottomNavigationItemView item : mButtons) {
        if (item != null) {
          mItemPool.release(item);
        }
      }
    }
    if (mMenu.size() == 0) {
      mSelectedItemId = 0;
      mSelectedItemPosition = 0;
      mButtons = null;
      return;
    }
    mButtons = new BottomNavigationItemView[mMenu.size()];
    boolean shifting = isShifting(mShiftingModeFlag, mMenu.getVisibleItems().size());
    for (int i = 0; i < mMenu.size(); i++) {
        mPresenter.setUpdateSuspended(true);
        mMenu.getItem(i).setCheckable(true);
        mPresenter.setUpdateSuspended(false);
        BottomNavigationItemView child = getNewItem();
        mButtons[i] = child;
        child.setIconTintList(mItemIconTint);
        child.setTextColor(mItemTextColor);
        child.setItemBackground(mItemBackgroundRes);
        child.setShiftingMode(shifting);
        child.initialize((MenuItemImpl) mMenu.getItem(i), 0);
        child.setItemPosition(i);
        child.setOnClickListener(mOnClickListener);
        addView(child);
    }
    mSelectedItemPosition = Math.min(mMenu.size() - 1, mSelectedItemPosition);
    mMenu.getItem(mSelectedItemPosition).setChecked(true);
  }

  public void updateMenuView() {
    if (mMenu == null || mButtons == null) {
      return;
    }

    final int menuSize = mMenu.size();
    if (menuSize != mButtons.length) {
      // The size has changed. Rebuild menu view from scratch.
      buildMenuView();
      return;
    }

    int previousSelectedId = mSelectedItemId;

    for (int i = 0; i < menuSize; i++) {
      MenuItem item = mMenu.getItem(i);
      if (item.isChecked()) {
        mSelectedItemId = item.getItemId();
        mSelectedItemPosition = i;
      }
    }
    if (previousSelectedId != mSelectedItemId) {
      // Note: this has to be called before BottomNavigationItemView#initialize().
      TransitionManager.beginDelayedTransition(this, mSet);
    }

    boolean shifting = isShifting(mShiftingModeFlag, mMenu.getVisibleItems().size());
    for (int i = 0; i < menuSize; i++) {
      mPresenter.setUpdateSuspended(true);
      mButtons[i].setShiftingMode(shifting);
      mButtons[i].initialize((MenuItemImpl) mMenu.getItem(i), 0);
      mPresenter.setUpdateSuspended(false);
    }
  }

  private BottomNavigationItemView getNewItem() {
    BottomNavigationItemView item = mItemPool.acquire();
    if (item == null) {
      item = new BottomNavigationItemView(getContext());
    }
    return item;
  }

  public int getSelectedItemId() {
    return mSelectedItemId;
  }

  private boolean isShifting(@ShiftingMode int shiftingMode, int childCount) {
    return shiftingMode == BottomNavigationView.SHIFTING_MODE_AUTO
        ? childCount > 3
        : shiftingMode == BottomNavigationView.SHIFTING_MODE_ON;
  }

  void tryRestoreSelectedItemId(int itemId) {
    final int size = mMenu.size();
    for (int i = 0; i < size; i++) {
      MenuItem item = mMenu.getItem(i);
      if (itemId == item.getItemId()) {
        mSelectedItemId = itemId;
        mSelectedItemPosition = i;
        item.setChecked(true);
        break;
      }
    }
  }
}
