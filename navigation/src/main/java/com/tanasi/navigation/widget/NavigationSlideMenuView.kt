package com.tanasi.navigation.widget

import android.content.Context
import android.graphics.drawable.ShapeDrawable
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.MenuView
import androidx.core.view.forEachIndexed

class NavigationSlideMenuView(
    context: Context,
) : LinearLayout(context), MenuView {

    lateinit var navigationSlideView: NavigationSlideView

    private var childs = mutableListOf<NavigationSlideItemView>()
    var selectedItemId = 0
    var selectedItemPosition = 0

    lateinit var presenter: NavigationSlidePresenter
    private lateinit var menu: MenuBuilder

    private val layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    var menuGravity: Int
        get() = layoutParams.gravity
        set(value) {
            if (layoutParams.gravity != value) {
                layoutParams.gravity = value
                setLayoutParams(layoutParams)
            }
        }

    var menuSpacing: Int
        get() = dividerDrawable.intrinsicHeight
        set(value) {
            dividerDrawable = ShapeDrawable().apply {
                alpha = 0
                intrinsicHeight = value
            }
            showDividers = SHOW_DIVIDER_MIDDLE
        }


    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setLayoutParams(layoutParams)
    }


    override fun initialize(menu: MenuBuilder) {
        this.menu = menu
    }

    override fun getWindowAnimations(): Int = 0


    fun buildMenuView() {
        removeAllViews()
        childs.clear()

        if (!::menu.isInitialized || menu.size() == 0) {
            if (::navigationSlideView.isInitialized) {
                navigationSlideView.buildNavigation()
            }
            return
        }

        menu.forEachIndexed { i, item ->
            presenter.updateSuspended = true
            item.isCheckable = true
            presenter.updateSuspended = false

            val child = NavigationSlideItemView(context)
            childs.add(child)

            child.isFocusable = true
            child.isFocusableInTouchMode = true

            child.initialize(item as MenuItemImpl, 0)
            child.itemPosition = i

            addView(child)
        }

        val index = visibleSelection(selectedItemPosition)
        selectedItemPosition = index
        selectedItemId = menu.getItem(index).itemId
        // Check before wiring focus so the collapsed rail keeps this row focusable.
        menu.getItem(index).isChecked = true
        navigationSlideView.buildNavigation()
    }

    fun updateMenuView() {
        if (menu.size() != childs.size) {
            // The size has changed. Rebuild menu view from scratch.
            buildMenuView()
            return
        }
        if (menu.size() == 0) return

        var checked = -1
        menu.forEachIndexed { i, item ->
            if (item.isChecked && item.isVisible) {
                checked = i
            }
        }
        if (checked == -1) {
            checked = visibleSelection(selectedItemPosition)
            presenter.updateSuspended = true
            menu.getItem(checked).isChecked = true
            presenter.updateSuspended = false
        }
        selectedItemPosition = checked
        selectedItemId = menu.getItem(checked).itemId

        childs.forEachIndexed { i, child ->
            presenter.updateSuspended = true
            child.initialize((menu.getItem(i) as MenuItemImpl), 0)
            presenter.updateSuspended = false
        }

        navigationSlideView.buildNavigation()
    }

    override fun hasFocus() = childs.any { it.hasFocus() }

    fun preferredFocusTarget(): View? {
        if (childs.isEmpty()) return null
        val index = selectedItemPosition.coerceIn(0, childs.lastIndex)
        val selected = childs[index]
        if (selected.visibility == View.VISIBLE) {
            selected.isFocusable = true
            selected.isFocusableInTouchMode = true
            return selected
        }
        return childs.firstOrNull { it.visibility == View.VISIBLE }?.also {
            it.isFocusable = true
            it.isFocusableInTouchMode = true
        }
    }

    fun open() {
        childs.forEach {
            it.isFocusable = true
            it.isFocusableInTouchMode = true

            it.open()
        }
        wireVerticalFocus()
    }

    fun close() {
        childs.forEach {
            it.isFocusable = it.isSelected && it.visibility == View.VISIBLE
            it.isFocusableInTouchMode = it.isFocusable

            it.close()
        }
        wireVerticalFocus()
    }

    private fun visibleSelection(preferred: Int): Int {
        val clamped = preferred.coerceIn(0, menu.size() - 1)
        if (menu.getItem(clamped).isVisible) return clamped
        return (0 until menu.size()).firstOrNull { menu.getItem(it).isVisible } ?: clamped
    }

    private fun wireVerticalFocus() {
        val header = navigationSlideView.headerView
        if (header != null && header.id == View.NO_ID) {
            header.id = View.generateViewId()
        }
        val chain = childs.filter { it.visibility == View.VISIBLE && it.isFocusable }
        childs.filter { it !in chain }.forEach {
            it.nextFocusUpId = View.NO_ID
            it.nextFocusDownId = View.NO_ID
        }
        chain.forEachIndexed { index, child ->
            val above = chain.getOrNull(index - 1)
            child.nextFocusUpId = above?.id
                ?: header?.takeIf { it.isFocusable }?.id
                ?: View.NO_ID
            child.nextFocusDownId = chain.getOrNull(index + 1)?.id ?: View.NO_ID
            child.nextFocusLeftId = View.NO_ID
            child.nextFocusRightId = View.NO_ID
        }
        if (header != null) {
            header.nextFocusDownId = chain.firstOrNull()?.id ?: View.NO_ID
            header.nextFocusUpId = View.NO_ID
            header.nextFocusLeftId = View.NO_ID
            header.nextFocusRightId = View.NO_ID
        }
    }

    fun forEach(action: (child: NavigationSlideItemView, item: MenuItem) -> Unit) {
        childs.forEach {
            action(it, it.itemData)
        }
    }
}