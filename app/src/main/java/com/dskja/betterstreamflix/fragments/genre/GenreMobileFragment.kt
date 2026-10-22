package com.dskja.betterstreamflix.fragments.genre

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentGenreMobileBinding
import com.dskja.betterstreamflix.databinding.HeaderGenreMobileBinding
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.DetailHeaderController
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class GenreMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentGenreMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<GenreMobileFragmentArgs>()
    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory { GenreViewModel(args.id, database, args.name) }

    private val appAdapter = AppAdapter()

    /** Vertical scroll state kept across view recreation (detail → back). */
    private var gridListState: android.os.Parcelable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGenreMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_genre_mobile,
                container,
                false,
            )
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpMotion.enterScreen(binding.root)
        ExpMotion.staggerFirstFill(binding.rvGenre)
        DetailHeaderController.wireBack(binding.root)

        initializeGenre()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    GenreViewModel.State.Loading -> binding.isLoading.apply {
                        binding.root.findViewById<View>(R.id.tv_genre_empty)?.visibility = View.GONE
                        binding.root.findViewById<View>(R.id.v_genre_empty_rule)?.visibility = View.GONE
                        binding.root.findViewById<View>(R.id.btn_genre_empty_cta)?.visibility = View.GONE
                        ExpMotion.fadeInAndShow(root)
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    GenreViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is GenreViewModel.State.SuccessLoading -> {
                        displayGenre(state.genre, state.hasMore)
                        appAdapter.isLoading = false
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                    }
                    is GenreViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getGenre(args.id) }) {
                                return@collect
                            }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                                gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getGenre(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    com.dskja.betterstreamflix.utils.LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        gridListState = _binding?.rvGenre?.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
        _binding = null
    }


    private fun initializeGenre() {
        binding.rvGenre.apply {
            layoutManager = GridLayoutManager(context, 3).also {
                it.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val viewType = appAdapter.getItemViewType(position)
                        return when (AppAdapter.Type.entries[viewType]) {
                            AppAdapter.Type.HEADER -> it.spanCount
                            else -> 1
                        }
                    }
                }
            }
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
    }

    private fun displayGenre(genre: Genre, hasMore: Boolean) {
        appAdapter.setHeader(
            binding = { parent ->
                HeaderGenreMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.header_genre_mobile,
                        parent,
                        false,
                    )
                )
            },
            bind = { binding ->
                binding.tvGenreName.text = genre.name.takeIf { it.isNotEmpty() } ?: args.name
            }
        )

        appAdapter.submitList(genre.shows.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }
        })
        gridListState?.let { state ->
            binding.rvGenre.post {
                _binding?.rvGenre?.layoutManager?.onRestoreInstanceState(state)
            }
            gridListState = null
        }

        val isEmpty = genre.shows.isEmpty()
        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_genre_empty),
            emptyRule = binding.root.findViewById(R.id.v_genre_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_genre_empty_cta),
            visible = isEmpty,
            tintOnSurfaceVariant = false,
            onCtaClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
        )
        binding.root.findViewById<View>(R.id.v_genre_empty_rule)?.visibility =
            if (isEmpty) View.VISIBLE else View.GONE
        binding.root.findViewById<View>(R.id.btn_genre_empty_cta)?.apply {
            visibility = if (isEmpty) View.VISIBLE else View.GONE
            setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigateUp()
            }
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreGenreShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}
