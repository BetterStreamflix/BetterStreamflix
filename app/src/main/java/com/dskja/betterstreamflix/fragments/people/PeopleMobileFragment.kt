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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.FragmentPeopleMobileBinding
import com.dskja.betterstreamflix.databinding.HeaderPeopleMobileBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.Http409CacheGuard
import com.dskja.betterstreamflix.utils.ExpNavAutoHide
import com.dskja.betterstreamflix.utils.ExpEmptyChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.LoggingUtils
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class PeopleMobileFragment : Fragment() {

    private val http409Guard = Http409CacheGuard()

    private companion object {
        const val COLLAPSED_BIOGRAPHY_LINES = 4
    }

    private var _binding: FragmentPeopleMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<PeopleMobileFragmentArgs>()
    private val database get() = AppDatabase.getInstance(requireContext())
    private val viewModel by viewModelsFactory { PeopleViewModel(args.id, database) }

    private val appAdapter = AppAdapter()

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
        ExpNavAutoHide.attach(binding.root)
        com.dskja.betterstreamflix.utils.ExpPressEffects.wireLoadingRetry(binding.isLoading.root)
        ExpMotion.enterScreen(binding.root)
        ExperimentalMobileDesign.applyReducedGlass(binding.root)
        ExpMotion.staggerFirstFill(binding.rvPeople)
        binding.root.findViewById<View>(R.id.iv_detail_back)?.also { back ->
            androidx.appcompat.widget.TooltipCompat.setTooltipText(
                back, back.context.getString(R.string.exp_back),
            )
        if (ExperimentalMobileDesign.enabled()) {
                back.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { back.applyExpPress() }
                ExpMotion.popIn(back)
            }
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
                        com.dskja.betterstreamflix.utils.ExpPressEffects.showLoadingSkeleton(root, true)
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
                        if (!ExperimentalMobileDesign.enabled()) {
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
                                val doRetry = { viewModel.getPeople(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(requireContext(), getString(com.dskja.betterstreamflix.R.string.clear_cache_done), com.dskja.betterstreamflix.R.string.loading_error_clear_cache)
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
                binding.ivPeopleImage.apply {
                    clipToOutline = true
                    Glide.with(context)
                        .load(people.image ?: args.image)
                        .placeholder(R.drawable.ic_person_placeholder)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(this)
                }

                binding.tvPeopleName.text = people.name.takeIf { it.isNotEmpty() } ?: args.name

                if (ExperimentalMobileDesign.enabled() &&
                    binding.root.getTag(R.id.exp_enter_animated_tag) != true
                ) {
                    binding.root.setTag(R.id.exp_enter_animated_tag, true)
                    ExpMotion.kenBurns(binding.ivPeopleImage)
                    ExpMotion.revealHeader(
                        binding.root.findViewById(R.id.tv_people_eyebrow),
                        binding.tvPeopleName,
                        binding.root.findViewById(R.id.v_people_rule),
                    )
                    ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_people_rule))
                    binding.root.findViewById<View>(R.id.tv_people_filmography_label)?.let { label ->
                        label.visibility = View.VISIBLE
                        ExpMotion.revealHeader(label)
                    }
                    ExpMotion.pulseAccentRule(
                        binding.root.findViewById(R.id.v_people_filmography_rule),
                    )
                    binding.root.findViewById<View>(R.id.v_people_ring)?.let { ring ->
                        ring.animate().cancel()
                        ring.scaleX = 0.92f
                        ring.scaleY = 0.92f
                        ring.alpha = 0.55f
                        ring.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(480L)
                            .start()
                    }
                } else if (ExperimentalMobileDesign.enabled()) {
                    binding.root.findViewById<View>(R.id.tv_people_filmography_label)?.visibility =
                        View.VISIBLE
                }

                binding.tvPeopleBirthday.text = people.birthday?.format("MMMM dd, yyyy")

                binding.gPeopleBirthday.visibility = when {
                    binding.tvPeopleBirthday.text.isNullOrEmpty() -> View.GONE
                    else -> View.VISIBLE
                }
                if (ExperimentalMobileDesign.enabled() &&
                    binding.gPeopleBirthday.visibility == View.VISIBLE &&
                    binding.gPeopleBirthday.getTag(R.id.exp_enter_animated_tag) != true
                ) {
                    binding.gPeopleBirthday.setTag(R.id.exp_enter_animated_tag, true)
                    val padH = (8 * resources.displayMetrics.density).toInt()
                    val padV = (3 * resources.displayMetrics.density).toInt()
                    binding.tvPeopleBirthday.setBackgroundResource(
                        ExperimentalMobileDesign.metaPillBackground(),
                    )
                    binding.tvPeopleBirthday.setPadding(padH, padV, padH, padV)
                    ExpMotion.revealHeader(
                        binding.root.findViewById(R.id.tv_people_birthday_label),
                        binding.tvPeopleBirthday,
                    )
                }

                binding.tvPeopleDeathday.text = people.deathday?.format("MMMM dd, yyyy")

                binding.gPeopleDeathday.visibility = when {
                    binding.tvPeopleDeathday.text.isNullOrEmpty() -> View.GONE
                    else -> View.VISIBLE
                }
                if (ExperimentalMobileDesign.enabled() &&
                    binding.gPeopleDeathday.visibility == View.VISIBLE &&
                    binding.gPeopleDeathday.getTag(R.id.exp_enter_animated_tag) != true
                ) {
                    binding.gPeopleDeathday.setTag(R.id.exp_enter_animated_tag, true)
                    val padH = (8 * resources.displayMetrics.density).toInt()
                    val padV = (3 * resources.displayMetrics.density).toInt()
                    binding.tvPeopleDeathday.setBackgroundResource(
                        ExperimentalMobileDesign.metaPillBackground(),
                    )
                    binding.tvPeopleDeathday.setPadding(padH, padV, padH, padV)
                    ExpMotion.revealHeader(
                        binding.root.findViewById(R.id.tv_people_deathday_label),
                        binding.tvPeopleDeathday,
                    )
                }

                binding.tvPeopleBirthplace.text = people.placeOfBirth

                binding.gPeopleBirthplace.visibility = when {
                    binding.tvPeopleBirthplace.text.isNullOrEmpty() -> View.GONE
                    else -> View.VISIBLE
                }
                if (ExperimentalMobileDesign.enabled() &&
                    binding.gPeopleBirthplace.visibility == View.VISIBLE &&
                    binding.gPeopleBirthplace.getTag(R.id.exp_enter_animated_tag) != true
                ) {
                    binding.gPeopleBirthplace.setTag(R.id.exp_enter_animated_tag, true)
                    val padH = (8 * resources.displayMetrics.density).toInt()
                    val padV = (3 * resources.displayMetrics.density).toInt()
                    binding.tvPeopleBirthplace.setBackgroundResource(
                        ExperimentalMobileDesign.metaPillBackground(),
                    )
                    binding.tvPeopleBirthplace.setPadding(padH, padV, padH, padV)
                    ExpMotion.revealHeader(
                        binding.root.findViewById(R.id.tv_people_birthplace_label),
                        binding.tvPeopleBirthplace,
                    )
                }

                binding.tvPeopleBiography.apply {
                    text = people.biography
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                }

                binding.tvPeopleBiographyReadMore.apply {
                    if (ExperimentalMobileDesign.enabled()) {
                        setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
                    }
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
                            binding.tvPeopleBiography.lineCount > COLLAPSED_BIOGRAPHY_LINES
                        val wasVisible = visibility == View.VISIBLE
                        visibility = if (overflowing) View.VISIBLE else View.GONE
                        if (!overflowing) {
                            binding.tvPeopleBiography.maxLines = Int.MAX_VALUE
                            binding.tvPeopleBiography.ellipsize = null
                        }
                        if (ExperimentalMobileDesign.enabled() && overflowing && !wasVisible) {
                            ExpMotion.popIn(this)
                        }
                    }
                }

                binding.gPeopleBiography.visibility = when {
                    binding.tvPeopleBiography.text.isNullOrEmpty() -> View.GONE
                    else -> View.VISIBLE
                }
                if (ExperimentalMobileDesign.enabled() && binding.gPeopleBiography.visibility == View.VISIBLE) {
                    binding.tvPeopleBiography.setBackgroundResource(
                        ExperimentalMobileDesign.glassCardBackground(),
                    )
                    ExpMotion.revealHeader(
                        binding.tvPeopleBiographyLabel,
                        binding.tvPeopleBiography,
                    )
                }
            }
        )

        appAdapter.submitList(people.filmography.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }
        })

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