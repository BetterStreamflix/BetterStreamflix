package com.tanasi.navigation.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.res.getResourceIdOrThrow
import com.tanasi.navigation.R

class NavigationSlideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    val menu = NavigationSlideMenu(context)
    var headerView: NavigationSlideHeaderView? = null
    val menuView = NavigationSlideMenuView(context).also {
        it.navigationSlideView = this
    }
    private val presenter = NavigationSlidePresenter()
    private val menuInflater: MenuInflater = SupportMenuInflater(context)

    var isOpen = false

    private var selectedListener: ((item: MenuItem) -> Boolean)? = null
    private var reselectedListener: ((item: MenuItem) -> Boolean)? = null

    private val settleChrome = Runnable {
        val inside = headerView?.hasFocus() == true || menuView.hasFocus()
        if (inside) open() else close()
    }

    /**
     * Currently selected menu item ID, or zero if there is no menu.
     */
    var selectedItemId: Int
        get() = menuView.selectedItemId
        set(value) {
            val item = menu.findItem(value)
            if (item != null) {
                if (!menu.performItemAction(item, presenter, 0)) {
                    item.isChecked = true
                }
            }
        }

    /**
     * Current gravity setting for how destinations in the menu view will be grouped.
     */
    private var menuGravity: Int
        get() = menuView.menuGravity
        set(value) {
            menuView.menuGravity = value
        }

    /**
     * Current spacing setting for how destinations in the menu view will be spaced out.
     */
    private var menuSpacing: Int
        get() = menuView.menuSpacing
        set(value) {
            menuView.menuSpacing = value
        }


    init {
        val attributes = context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.NavigationSlideView,
            0,
            0
        )

        presenter.menuView = menuView
        menuView.presenter = presenter
        menu.addMenuPresenter(presenter)
        presenter.initForMenu(getContext(), menu)

        val headerLayoutRes = attributes.getResourceId(
            R.styleable.NavigationSlideView_headerLayout,
            0
        )
        if (headerLayoutRes != 0) {
            addHeaderView(headerLayoutRes)
        }

        menuGravity = attributes.getInt(
            R.styleable.NavigationSlideView_menuGravity,
            DEFAULT_MENU_GRAVITY
        )

        menuSpacing = attributes.getDimensionPixelSize(
            R.styleable.NavigationSlideView_menuSpacing,
            DEFAULT_MENU_SPACING
        )

        inflateMenu(attributes.getResourceIdOrThrow(R.styleable.NavigationSlideView_menu))

        attributes.recycle()

        clipChildren = false
        clipToPadding = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        addView(menuView)

        menu.setCallback(object : MenuBuilder.Callback {
            override fun onMenuItemSelected(menu: MenuBuilder, item: MenuItem): Boolean {
                if (reselectedListener != null && item.itemId == selectedItemId) {
                    reselectedListener?.invoke(item)
                    return true
                }
                return !(selectedListener?.invoke(item) ?: true)
            }

            override fun onMenuModeChange(menu: MenuBuilder) {}
        })
    }


    fun setOnItemSelectedListener(onNavigationItemSelected: (item: MenuItem) -> Boolean) {
        selectedListener = onNavigationItemSelected
    }

    fun setOnItemReselectedListener(onNavigationItemReselected: (item: MenuItem) -> Boolean) {
        reselectedListener = onNavigationItemReselected
    }

    /**
     * Inflate a menu resource into this navigation view.
     *
     *
     * Existing items in the menu will not be modified or removed.
     *
     * @param resId ID of a menu resource to inflate
     */
    fun inflateMenu(resId: Int) {
        presenter.updateSuspended = true
        menuInflater.inflate(resId, menu)
        presenter.updateSuspended = false
        presenter.updateMenuView(true)
    }

    fun buildNavigation() {
        headerView?.let { bindChromeListener(it) }
        menuView.forEach { child, item ->
            bindChromeListener(child)

            child.setOnClickListener {
                if (!menu.performItemAction(item, presenter, 0)) {
                    item.isChecked = true
                }
            }
        }

        when {
            isOpen -> open()
            else -> close()
        }
    }

    fun addHeaderView(@LayoutRes layoutRes: Int) {
        val headerView = LayoutInflater.from(context).inflate(layoutRes, this, false)
        if (headerView is NavigationSlideHeaderView) {
            addHeaderView(headerView)
        }
    }

    fun addHeaderView(headerView: NavigationSlideHeaderView) {
        removeHeaderView()
        this.headerView = headerView
        addView(headerView, 0)
    }

    fun removeHeaderView() {
        if (headerView != null) {
            removeView(headerView)
            headerView = null
        }
    }

    /**
     * DPAD into the rail from a row that is not vertically aligned with the
     * checked icon. The rail is full height; forwarding focus to the checked
     * row keeps Search / Home / Movies reachable from every shelf.
     */
    override fun addFocusables(views: java.util.ArrayList<View>, direction: Int, focusableMode: Int) {
        if (visibility != View.VISIBLE) return
        val towardMenu = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            View.FOCUS_RIGHT
        } else {
            View.FOCUS_LEFT
        }
        if (direction == towardMenu && !hasFocus()) {
            isFocusable = true
            views.add(this)
            post {
                if (!isFocused) isFocusable = false
            }
            return
        }
        isFocusable = false
        super.addFocusables(views, direction, focusableMode)
    }

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        isFocusable = false
        val target = menuView.preferredFocusTarget()
        if (target != null && target.requestFocus()) return true
        return super.requestFocus(direction, previouslyFocusedRect)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val header = headerView
        var contentTop = paddingTop
        if (header != null && header.visibility != View.GONE) {
            val lp = header.layoutParams as MarginLayoutParams
            val headerLeft = paddingLeft + lp.leftMargin
            val headerTop = paddingTop + lp.topMargin
            header.layout(
                headerLeft,
                headerTop,
                headerLeft + header.measuredWidth,
                headerTop + header.measuredHeight,
            )
            contentTop = headerTop + header.measuredHeight + lp.bottomMargin
        }

        val menu = menuView
        val lp = menu.layoutParams as MarginLayoutParams
        val menuLeft = paddingLeft + lp.leftMargin
        val availableBottom = bottom - top - paddingBottom - lp.bottomMargin
        val space = (availableBottom - contentTop - menu.measuredHeight).coerceAtLeast(0)
        val gravity = menuGravity
        val offset = when {
            gravity and Gravity.BOTTOM == Gravity.BOTTOM -> space
            gravity and Gravity.CENTER_VERTICAL == Gravity.CENTER_VERTICAL -> space / 2
            else -> 0
        }
        val menuTop = contentTop + lp.topMargin + offset
        menu.layout(
            menuLeft,
            menuTop,
            menuLeft + menu.measuredWidth,
            menuTop + menu.measuredHeight,
        )
    }

    fun open() {
        isOpen = true

        headerView?.open()
        menuView.open()
    }

    fun close() {
        isOpen = false

        headerView?.close()
        menuView.close()
    }

    private fun bindChromeListener(view: View) {
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // Cancel the close posted by the row we just left, or the next
                // row is marked unfocusable before it can take focus.
                removeCallbacks(settleChrome)
                open()
            } else {
                removeCallbacks(settleChrome)
                post(settleChrome)
            }
        }
    }


    companion object {
        const val DEFAULT_MENU_GRAVITY = Gravity.TOP or Gravity.START
        const val DEFAULT_MENU_SPACING = 0
    }
}