package com.dskja.betterstreamflix.fragments.people

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
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentPeopleTvBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.viewModelsFactory
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class PeopleTvFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private var _binding: FragmentPeopleTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<PeopleTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PeopleViewModel(args.id, database) }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPeopleTvBinding.inflate(inflater, container, false)
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
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    PeopleViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is PeopleViewModel.State.SuccessLoading -> {
                        displayPeople(state.people, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(
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
                        if (!com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                            Toast.makeText(
                                requireContext(),
                                state.error.message ?: "",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, false)
                                gIsLoadingRetry.visibility = View.VISIBLE
                                com.dskja.betterstreamflix.utils.ExpPressEffects.animateLoadingError(root)
                                btnIsLoadingRetry.setOnClickListener { viewModel.getPeople(args.id) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializePeople() {
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.enterScreen(binding.root)
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                binding.tvPeopleName,
                com.google.android.material.R.attr.colorOnSurface,
            )
            val onVariant = com.google.android.material.color.MaterialColors.getColor(
                binding.tvPeopleBirthday,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            binding.tvPeopleName.setTextColor(onSurface)
            listOf(
                binding.tvPeopleBirthdayLabel,
                binding.tvPeopleDeathdayLabel,
                binding.tvPeopleBirthplaceLabel,
            ).forEach { it.setTextColor(onSurface) }
            listOf(
                binding.tvPeopleBirthday,
                binding.tvPeopleDeathday,
                binding.tvPeopleBirthplace,
            ).forEach { it.setTextColor(onVariant) }
            ExpMotion.revealHeader(binding.tvPeopleName)
        }
        binding.vgvPeopleFilmography.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(20)
        }
    }

    private fun displayPeople(people: People, hasMore: Boolean) {
        binding.tvPeopleName.text = people.name.takeIf { it.isNotEmpty() } ?: args.name

        binding.ivPeopleImage.apply {
            clipToOutline = true
            Glide.with(context)
                .load(people.image ?: args.image)
                .placeholder(R.drawable.ic_person_placeholder)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
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

        appAdapter.submitList(people.filmography.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

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
}