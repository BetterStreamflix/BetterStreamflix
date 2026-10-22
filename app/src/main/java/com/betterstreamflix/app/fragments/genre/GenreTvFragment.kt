package com.betterstreamflix.app.fragments.genre

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.FragmentGenreTvBinding
import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.ExpEmptyChrome
import com.betterstreamflix.app.utils.Http409CacheGuard
import com.betterstreamflix.app.utils.viewModelsFactory
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class GenreTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentGenreTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<GenreTvFragmentArgs>()
    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory { GenreViewModel(args.id, database) }

    private val appAdapter = AppAdapter()

    private var savedGridPosition = RecyclerView.NO_POSITION

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGenreTvBinding.inflate(inflater, container, false)
        if (savedInstanceState != null) {
            savedGridPosition = savedInstanceState.getInt(KEY_GRID_POSITION, RecyclerView.NO_POSITION)
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeGenre()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    GenreViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    GenreViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is GenreViewModel.State.SuccessLoading -> {
                        displayGenre(state.genre, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                    }
                    is GenreViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) {
                                if (appAdapter.isLoading) appAdapter.isLoading = false
                                                            viewModel.getGenre(args.id)
                            }) {
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
                                com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getGenre(args.id) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.betterstreamflix.app.utils.ExpDialogChrome.notify(requireContext(), getString(com.betterstreamflix.app.R.string.clear_cache_done), com.betterstreamflix.app.R.string.loading_error_clear_cache)
                                    viewModel.getGenre(args.id)
                                }
                                btnIsLoadingRetry.requestFocus()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val position = _binding?.vgvGenre?.selectedPosition ?: savedGridPosition
        outState.putInt(KEY_GRID_POSITION, position)
    }

    override fun onDestroyView() {
        _binding?.let {
            savedGridPosition = it.vgvGenre.selectedPosition
            appAdapter.onSaveInstanceState(it.vgvGenre)
        }
        _binding = null
        super.onDestroyView()
    }

    private fun initializeGenre() {
        binding.vgvGenre.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setNumColumns(com.betterstreamflix.app.ui.LeanbackDensityPolicy.posterGridColumns())
            setItemSpacing(
                com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
                    this,
                    com.betterstreamflix.app.ui.LeanbackDensityPolicy.catalogItemSpacingDp(),
                ),
            )
        }
    }

    private fun displayGenre(genre: Genre, hasMore: Boolean) {
        binding.tvGenreName.text = getString(
            R.string.genre_header_name,
            genre.name.takeIf { it.isNotEmpty() } ?: args.name
        )

        appAdapter.submitList(genre.shows.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

        if (savedGridPosition != RecyclerView.NO_POSITION && genre.shows.isNotEmpty()) {
            val target = savedGridPosition.coerceIn(0, genre.shows.lastIndex)
            binding.vgvGenre.post {
                binding.vgvGenre.selectedPosition = target
            }
            savedGridPosition = RecyclerView.NO_POSITION
        }

        binding.tvGenreEmpty.visibility =
            if (genre.shows.isEmpty()) View.VISIBLE else View.GONE
        binding.vgvGenre.visibility =
            if (genre.shows.isEmpty()) View.GONE else View.VISIBLE
        ExpEmptyChrome.bind(
            emptyView = binding.tvGenreEmpty,
            emptyRule = binding.root.findViewById(R.id.v_genre_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_genre_empty_cta),
            visible = genre.shows.isEmpty(),
            onCtaClick = { findNavController().navigateUp() },
        )

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreGenreShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    companion object {
        private const val KEY_GRID_POSITION = "genre_tv_grid_position"
    }
}