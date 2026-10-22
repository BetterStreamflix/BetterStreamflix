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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.FragmentPeopleMobileBinding
import com.betterstreamflix.app.databinding.HeaderPeopleMobileBinding
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.ui.SpacingItemDecoration
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.Http409CacheGuard
import com.betterstreamflix.app.utils.ExpEmptyChrome
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.LoggingUtils
import com.betterstreamflix.app.utils.dp
import com.betterstreamflix.app.utils.format
import com.betterstreamflix.app.utils.viewModelsFactory
import kotlinx.coroutines.launch

class PeopleMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private companion object {
        const val COLLAPSED_BIOGRAPHY_LINES = 4
    }

    private var _binding: FragmentPeopleMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<PeopleMobileFragmentArgs>()
    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory { PeopleViewModel(args.id, database) }

    private val appAdapter = AppAdapter()

    /** Vertical scroll state kept across view recreation (detail → back). */
    private var gridListState: android.os.Parcelable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPeopleMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_people_mobile,
                container,
                false,
            )
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpMotion.enterScreen(binding.root)
        ExpMotion.staggerFirstFill(binding.rvPeople)
        binding.root.findViewById<View>(R.id.iv_detail_back)?.also { back ->
            androidx.appcompat.widget.TooltipCompat.setTooltipText(
                back, back.context.getString(R.string.exp_back),
            )
            back.setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigateUp()
            }
        }

        initializePeople()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    PeopleViewModel.State.Loading -> binding.isLoading.apply {
                        ExpMotion.fadeInAndShow(root)
                        com.betterstreamflix.app.utils.ExpPressEffects.showLoadingSkeleton(root, true)
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    PeopleViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is PeopleViewModel.State.SuccessLoading -> {
                        displayPeople(state.people, state.hasMore)
                        appAdapter.isLoading = false
                        ExpMotion.fadeOutAndHide(binding.isLoading.root)
                    }
                    is PeopleViewModel.State.FailedLoading -> {
                        if (http409Guard.handle(requireContext(), state.error) { viewModel.getPeople(args.id) }) {
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
                                val doRetry = { viewModel.getPeople(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.betterstreamflix.app.utils.ExpDialogChrome.notify(requireContext(), getString(com.betterstreamflix.app.R.string.clear_cache_done), com.betterstreamflix.app.R.string.loading_error_clear_cache)
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        gridListState = _binding?.rvPeople?.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
        _binding = null
    }


    private fun initializePeople() {
        binding.rvPeople.apply {
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

    private fun displayPeople(people: People, hasMore: Boolean) {
        appAdapter.setHeader(
            binding = { parent ->
                HeaderPeopleMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.header_people_mobile,
                        parent,
                        false,
                    )
                )
            },
            bind = { binding ->
                val portraitUrl = com.betterstreamflix.app.utils.ArtworkUrls.preferOriginal(
                    people.image ?: args.image,
                )

                // Atmosphere uses the same portrait (soft + cropped), not a random film still.
                binding.ivPeopleBackdrop.apply {
                    if (portraitUrl.isNullOrBlank()) {
                        setImageDrawable(null)
                        setBackgroundColor(0xFF121218.toInt())
                    } else {
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

                binding.tvPeopleName.text = people.name.takeIf { it.isNotEmpty() } ?: args.name

                binding.tvPeopleDepartment.apply {
                    val department = people.knownForDepartment?.takeIf { it.isNotBlank() }
                    text = department
                    visibility = if (department == null) View.GONE else View.VISIBLE
                }

                val born = people.birthday?.format("d MMM yyyy")
                val place = people.placeOfBirth?.takeIf { it.isNotBlank() }
                binding.tvPeopleBirthday.text = when {
                    born != null && place != null ->
                        getString(R.string.people_born) + " " + born + " · " + place
                    born != null -> getString(R.string.people_born) + " " + born
                    place != null -> place
                    else -> ""
                }
                binding.gPeopleBirthday.visibility =
                    if (binding.tvPeopleBirthday.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                binding.gPeopleBirthplace.visibility = View.GONE
                binding.tvPeopleMetaDot.visibility = View.GONE

                binding.tvPeopleDeathday.text = people.deathday
                    ?.format("d MMM yyyy")
                    ?.let { getString(R.string.people_deathday) + " " + it }
                binding.gPeopleDeathday.visibility =
                    if (binding.tvPeopleDeathday.text.isNullOrEmpty()) View.GONE else View.VISIBLE

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
                    setOnClickListener {
                        ExpMotion.hapticTap(it)
                        applyExpand(!expanded)
                    }
                    applyExpand(false)
                    binding.tvPeopleBiography.post {
                        val overflowing =
                            binding.tvPeopleBiography.lineCount > COLLAPSED_BIOGRAPHY_LINES ||
                                (people.biography?.length ?: 0) > 220
                        val wasVisible = visibility == View.VISIBLE
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
            }
        )

        appAdapter.submitList(people.filmography.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }
        })
        gridListState?.let { state ->
            binding.rvPeople.post {
                _binding?.rvPeople?.layoutManager?.onRestoreInstanceState(state)
            }
            gridListState = null
        }

        ExpEmptyChrome.bind(
            emptyView = binding.root.findViewById(R.id.tv_people_filmography_empty),
            emptyRule = binding.root.findViewById(R.id.v_people_empty_rule),
            emptyCta = binding.root.findViewById(R.id.btn_people_empty_cta),
            visible = people.filmography.isEmpty(),
            tintOnSurfaceVariant = false,
            onCtaClick = { requireActivity().onBackPressedDispatcher.onBackPressed() },
        )

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMorePeopleFilmography() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}