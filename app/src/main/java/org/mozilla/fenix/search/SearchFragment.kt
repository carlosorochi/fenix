/* This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.search

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface.BOLD
import android.graphics.Typeface.ITALIC
import android.os.Bundle
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_search.*
import kotlinx.android.synthetic.main.fragment_search.view.*
import mozilla.components.browser.search.SearchEngine
import mozilla.components.feature.qr.QrFeature
import mozilla.components.support.base.feature.BackHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.content.isPermissionGranted
import mozilla.components.support.ktx.kotlin.isUrl
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.components.toolbar.SearchAction
import org.mozilla.fenix.components.toolbar.SearchChange
import org.mozilla.fenix.components.toolbar.SearchState
import org.mozilla.fenix.components.toolbar.ToolbarComponent
import org.mozilla.fenix.components.toolbar.ToolbarUIView
import org.mozilla.fenix.ext.getSpannable
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.mvi.ActionBusFactory
import org.mozilla.fenix.mvi.getAutoDisposeObservable
import org.mozilla.fenix.mvi.getManagedEmitter
import org.mozilla.fenix.search.awesomebar.AwesomeBarAction
import org.mozilla.fenix.search.awesomebar.AwesomeBarChange
import org.mozilla.fenix.search.awesomebar.AwesomeBarComponent
import org.mozilla.fenix.search.awesomebar.AwesomeBarUIView

@Suppress("TooManyFunctions")
class SearchFragment : Fragment(), BackHandler {
    private lateinit var toolbarComponent: ToolbarComponent
    private lateinit var awesomeBarComponent: AwesomeBarComponent
    private var sessionId: String? = null
    private var isPrivate = false
    private val qrFeature = ViewBoundFeatureWrapper<QrFeature>()
    private var permissionDidUpdate = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        sessionId = SearchFragmentArgs.fromBundle(arguments!!).sessionId
        isPrivate = (activity as HomeActivity).browsingModeManager.isPrivate

        val session = sessionId?.let { requireComponents.core.sessionManager.findSessionById(it) }
        val view = inflater.inflate(R.layout.fragment_search, container, false)
        val url = session?.url ?: ""

        toolbarComponent = ToolbarComponent(
            view.toolbar_component_wrapper,
            this,
            ActionBusFactory.get(this),
            sessionId,
            isPrivate,
            SearchState(url, session?.searchTerms ?: "", isEditing = true),
            view.search_engine_icon
        )

        awesomeBarComponent = AwesomeBarComponent(view.search_layout, this, ActionBusFactory.get(this))
        ActionBusFactory.get(this).logMergedObservables()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutComponents(view.search_layout)

        qrFeature.set(
            QrFeature(
                requireContext(),
                fragmentManager = requireFragmentManager(),
                onNeedToRequestPermissions = { permissions ->
                    requestPermissions(permissions, REQUEST_CODE_CAMERA_PERMISSIONS)
                },
                onScanResult = { result ->
                    activity?.let {
                        AlertDialog.Builder(it).apply {
                            val spannable = resources.getSpannable(
                                R.string.qr_scanner_confirmation_dialog_message,
                                listOf(
                                    getString(R.string.app_name) to listOf(StyleSpan(BOLD)),
                                    result to listOf(StyleSpan(ITALIC))
                                )
                            )
                            setMessage(spannable)
                            setNegativeButton("DENY") { dialog: DialogInterface, _ ->
                                dialog.cancel()
                            }
                            setPositiveButton("ALLOW") { dialog: DialogInterface, _ ->
                                (activity as HomeActivity)
                                    .openToBrowserAndLoad(
                                        searchTermOrURL = result,
                                        newTab = sessionId == null,
                                        from = BrowserDirection.FromSearch
                                    )
                                dialog.dismiss()
                                // TODO add metrics
                            }
                            create()
                        }.show()
                    }
                }),
            owner = this,
            view = view
        )

        view.search_scan_button.setOnClickListener {
            getManagedEmitter<SearchChange>().onNext(SearchChange.ToolbarClearedFocus)
            qrFeature.get()?.scan(R.id.container)
        }

        lifecycle.addObserver((toolbarComponent.uiView as ToolbarUIView).toolbarIntegration)

        view.toolbar_wrapper.clipToOutline = false

        search_shortcuts_button.setOnClickListener {
            val isOpen = (awesomeBarComponent.uiView as AwesomeBarUIView).state?.showShortcutEnginePicker ?: false

            getManagedEmitter<AwesomeBarChange>().onNext(AwesomeBarChange.SearchShortcutEnginePicker(!isOpen))

            if (isOpen) {
                requireComponents.analytics.metrics.track(Event.SearchShortcutMenuClosed)
            } else {
                requireComponents.analytics.metrics.track(Event.SearchShortcutMenuOpened)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!permissionDidUpdate) {
            getManagedEmitter<SearchChange>().onNext(SearchChange.ToolbarRequestedFocus)
        }
        permissionDidUpdate = false
        (activity as AppCompatActivity).supportActionBar?.hide()
    }

    override fun onPause() {
        super.onPause()
        getManagedEmitter<SearchChange>().onNext(SearchChange.ToolbarClearedFocus)
    }

    override fun onStart() {
        super.onStart()
        subscribeToSearchActions()
        subscribeToAwesomeBarActions()
    }

    override fun onBackPressed(): Boolean {
        return when {
            qrFeature.onBackPressed() -> {
                view?.search_scan_button?.isChecked = false
                getManagedEmitter<SearchChange>().onNext(SearchChange.ToolbarRequestedFocus)
                true
            }
            else -> false
        }
    }

    private fun subscribeToSearchActions() {
        getAutoDisposeObservable<SearchAction>()
            .subscribe {
                when (it) {
                    is SearchAction.UrlCommitted -> {
                        if (it.url.isNotBlank()) {
                            (activity as HomeActivity).openToBrowserAndLoad(
                                searchTermOrURL = it.url,
                                newTab = sessionId == null,
                                from = BrowserDirection.FromSearch,
                                engine = it.engine
                            )

                            val event = if (it.url.isUrl()) {
                                Event.EnteredUrl(false)
                            } else {
                                val engine = it.engine ?: requireComponents
                                    .search.searchEngineManager.getDefaultSearchEngine(requireContext())

                                createSearchEvent(engine, false)
                            }

                            requireComponents.analytics.metrics.track(event)
                        }
                    }
                    is SearchAction.TextChanged -> {
                        getManagedEmitter<AwesomeBarChange>().onNext(AwesomeBarChange.UpdateQuery(it.query))
                    }
                    is SearchAction.EditingCanceled -> {
                        activity?.onBackPressed()
                    }
                }
            }
    }

    private fun subscribeToAwesomeBarActions() {
        getAutoDisposeObservable<AwesomeBarAction>()
            .subscribe {
                when (it) {
                    is AwesomeBarAction.URLTapped -> {
                        (activity as HomeActivity).openToBrowserAndLoad(
                            searchTermOrURL = it.url,
                            newTab = sessionId == null,
                            from = BrowserDirection.FromSearch
                        )
                        requireComponents.analytics.metrics.track(Event.EnteredUrl(false))
                    }
                    is AwesomeBarAction.SearchTermsTapped -> {
                        (activity as HomeActivity).openToBrowserAndLoad(
                            searchTermOrURL = it.searchTerms,
                            newTab = sessionId == null,
                            from = BrowserDirection.FromSearch,
                            engine = it.engine,
                            forceSearch = true
                        )

                        val engine = it.engine ?: requireComponents
                            .search.searchEngineManager.getDefaultSearchEngine(requireContext())
                        val event = createSearchEvent(engine, true)

                        requireComponents.analytics.metrics.track(event)
                    }
                    is AwesomeBarAction.SearchShortcutEngineSelected -> {
                        getManagedEmitter<AwesomeBarChange>()
                            .onNext(AwesomeBarChange.SearchShortcutEngineSelected(it.engine))
                        getManagedEmitter<SearchChange>()
                            .onNext(SearchChange.SearchShortcutEngineSelected(it.engine))

                        requireComponents.analytics.metrics.track(Event.SearchShortcutSelected(it.engine.name))
                    }
                }
            }
    }

    private fun createSearchEvent(engine: SearchEngine, isSuggestion: Boolean): Event.PerformedSearch {
        val isShortcut = engine != requireComponents.search.searchEngineManager.defaultSearchEngine

        val engineSource =
            if (isShortcut) Event.PerformedSearch.EngineSource.Shortcut(engine)
            else Event.PerformedSearch.EngineSource.Default(engine)

        val source =
            if (isSuggestion) Event.PerformedSearch.EventSource.Suggestion(engineSource)
            else Event.PerformedSearch.EventSource.Action(engineSource)

        return Event.PerformedSearch(source)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CODE_CAMERA_PERMISSIONS -> qrFeature.withFeature {
                it.onPermissionsResult(permissions, grantResults)

                context?.let { context: Context ->
                    if (context.isPermissionGranted(Manifest.permission.CAMERA)) {
                        permissionDidUpdate = true
                    }
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    companion object {
        private const val REQUEST_CODE_CAMERA_PERMISSIONS = 1
    }
}
