package com.betterstreamflix.app.fragments.people

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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.FragmentPeopleTvBinding
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.ExpEmptyChrome
import com.betterstreamflix.app.utils.Http409CacheGuard
import com.betterstreamflix.app.utils.LoggingUtils
import com.betterstreamflix.app.utils.format
import com.betterstreamflix.app.utils.viewModelsFactory
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class PeopleTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentPeopleTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<PeopleTvFragmentArgs>()
    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory { PeopleViewModel(args.id, database) }

    private val appAdapter = AppAdapter()

    private var savedGridPosition = RecyclerView.NO_POSITION

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPeopleTvBinding.inflate(inflater, container, false)
        if (savedInstanceState != null) {
            savedGridPosition = savedInstanceState.getInt(KEY_GRID_POSITION, RecyclerView.NO_POSITION)
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializePeople()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    PeopleViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    PeopleViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is PeopleViewModel.State.SuccessLoading -> {
                        displayPeople(state.people, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(
                            binding.isLoading.root, false,
                        )
                    }
                    is PeopleViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) {
                                if (appAdapter.isLoading) appAdapter.isLoading = false
                                                            viewModel.getPeople(args.id)
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
                                btnIsLoadingRetry.setOnClickListener { viewModel.getPeople(args.id) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.betterstreamflix.app.utils.ExpDialogChrome.notify(requireContext(), getString(com.betterstreamflix.app.R.string.clear_cache_done), com.betterstreamflix.app.R.string.loading_error_clear_cache)
                                    viewModel.getPeople(args.id)
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
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
        val position = _binding?.vgvPeopleFilmography?.selectedPosition ?: savedGridPosition
        outState.putInt(KEY_GRID_POSITION, position)
    }

    override fun onDestroyView() {
        _binding?.let {
            savedGridPosition = it.vgvPeopleFilmography.selectedPosition
            appAdapter.onSaveInstanceState(it.vgvPeopleFilmography)
        }
        _binding = null
        super.onDestroyView()
    }

    private fun initializePeople() {
        binding.vgvPeopleFilmography.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setNumColumns(com.betterstreamflix.app.ui.LeanbackDensityPolicy.posterGridColumns())
            setItemSpacing(
                com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
                    this,
                    com.betterstreamflix.app.ui.LeanbackDensityPolicy.peopleFilmographySpacingDp(),
                ),
            )
        }
    }

    private fun displayPeople(people: People, hasMore: Boolean) {
        binding.tvPeopleName.text = people.name.takeIf { it.isNotEmpty() } ?: args.name

        val portraitUrl = people.image ?: args.image
        binding.root.findViewById<android.widget.ImageView>(R.id.iv_people_backdrop)?.apply {
            if (portraitUrl.isNullOrBlank()) {
                setImageDrawable(null)
                visibility = View.GONE
                binding.root.findViewById<View>(R.id.v_people_backdrop_scrim)?.visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                binding.root.findViewById<View>(R.id.v_people_backdrop_scrim)?.visibility = View.VISIBLE
                Glide.with(context)
                    .load(portraitUrl)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(this)
            }
        }

        binding.ivPeopleImage.apply {
            clipToOutline = true
            Glide.with(context)
                .load(portraitUrl)
                .placeholder(R.drawable.ic_person_placeholder)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.root.findViewById<android.widget.TextView>(R.id.tv_people_department)?.apply {
            val department = people.knownForDepartment?.takeIf { it.isNotBlank() }
            text = department
            visibility = if (department == null) View.GONE else View.VISIBLE
        }

        binding.tvPeopleBirthday.text = people.birthday?.format("MMMM dd, yyyy")

        binding.gPeopleBirthday.visibility = when {
            binding.tvPeopleBirthday.text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }

        binding.tvPeopleDeathday.text = people.deathday?.format("MMMM dd, yyyy")

        binding.gPeopleDeathday.visibility = when {
            binding.tvPeopleDeathday.text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }

        binding.tvPeopleBirthplace.text = people.placeOfBirth

        binding.gPeopleBirthplace.visibility = when {
            binding.tvPeopleBirthplace.text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }

        binding.tvPeopleBiography.apply {
            text = people.biography
            maxLines = COLLAPSED_BIOGRAPHY_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        binding.tvPeopleBiographyReadMore.apply {
            var expanded = false
            fun applyExpand(open: Boolean) {
                expanded = open
                binding.tvPeopleBiography.maxLines =
                    if (open) Int.MAX_VALUE else COLLAPSED_BIOGRAPHY_LINES
                binding.tvPeopleBiography.ellipsize =
                    if (open) null else android.text.TextUtils.TruncateAt.END
                text = context.getString(
                    if (open) R.string.people_read_less else R.string.people_read_more,
                )
            }
            setOnClickListener { applyExpand(!expanded) }
            applyExpand(false)
            binding.tvPeopleBiography.post {
                val overflowing =
                    binding.tvPeopleBiography.lineCount > COLLAPSED_BIOGRAPHY_LINES ||
                        (people.biography?.length ?: 0) > 220
                visibility = if (overflowing) View.VISIBLE else View.GONE
                if (!overflowing) {
                    binding.tvPeopleBiography.maxLines = Int.MAX_VALUE
                    binding.tvPeopleBiography.ellipsize = null
                }
            }
        }

        binding.gPeopleBiography.visibility = when {
            binding.tvPeopleBiography.text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }

        appAdapter.submitList(people.filmography.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

        if (savedGridPosition != RecyclerView.NO_POSITION && people.filmography.isNotEmpty()) {
            val target = savedGridPosition.coerceIn(0, people.filmography.lastIndex)
            binding.vgvPeopleFilmography.post {
                binding.vgvPeopleFilmography.selectedPosition = target
            }
            savedGridPosition = RecyclerView.NO_POSITION
        }

        val empty = people.filmography.isEmpty()
        binding.tvPeopleFilmographyEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.vgvPeopleFilmography.visibility = if (empty) View.GONE else View.VISIBLE
        val emptyRule = binding.root.findViewById<View>(R.id.v_people_filmography_empty_rule)
        val emptyCta = binding.root.findViewById<View>(R.id.btn_people_filmography_empty_cta)
        ExpEmptyChrome.bind(
            emptyView = binding.tvPeopleFilmographyEmpty,
            emptyRule = emptyRule,
            emptyCta = emptyCta,
            visible = empty,
            onCtaClick = { findNavController().navigateUp() },
        )

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMorePeopleFilmography() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    companion object {
        private const val KEY_GRID_POSITION = "people_tv_grid_position"
        private const val COLLAPSED_BIOGRAPHY_LINES = 4
    }
}