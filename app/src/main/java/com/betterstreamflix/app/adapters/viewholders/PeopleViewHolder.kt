package com.betterstreamflix.app.adapters.viewholders

import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.TvFocusZoom
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.databinding.ItemPeopleMobileBinding
import com.betterstreamflix.app.databinding.ItemPeopleTvBinding
import com.betterstreamflix.app.fragments.movie.MovieMobileFragment
import com.betterstreamflix.app.fragments.movie.MovieMobileFragmentDirections
import com.betterstreamflix.app.fragments.movie.MovieTvFragment
import com.betterstreamflix.app.fragments.movie.MovieTvFragmentDirections
import com.betterstreamflix.app.fragments.search.SearchMobileFragment
import com.betterstreamflix.app.fragments.search.SearchMobileFragmentDirections
import com.betterstreamflix.app.fragments.search.SearchTvFragment
import com.betterstreamflix.app.fragments.search.SearchTvFragmentDirections
import com.betterstreamflix.app.fragments.tv_show.TvShowMobileFragment
import com.betterstreamflix.app.fragments.tv_show.TvShowMobileFragmentDirections
import com.betterstreamflix.app.fragments.tv_show.TvShowTvFragment
import com.betterstreamflix.app.fragments.tv_show.TvShowTvFragmentDirections
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.utils.getCurrentFragment
import com.betterstreamflix.app.utils.toActivity

class PeopleViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context

    private lateinit var people: People

    fun bind(people: People) {
        this.people = people

        when (_binding) {
            is ItemPeopleMobileBinding -> displayMobileItem(_binding)
            is ItemPeopleTvBinding -> displayTvItem(_binding)
        }
    }

    private fun displayMobileItem(binding: ItemPeopleMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                when (context.toActivity()?.getCurrentFragment()) {
                    is MovieMobileFragment -> findNavController().navigate(
                        MovieMobileFragmentDirections.actionMovieToPeople(
                            id = people.id,
                            name = people.name,
                            image = people.image,
                        )
                    )
                    is TvShowMobileFragment -> findNavController().navigate(
                        TvShowMobileFragmentDirections.actionTvShowToPeople(
                            id = people.id,
                            name = people.name,
                            image = people.image,
                        )
                    )
                    is SearchMobileFragment -> findNavController().navigate(
                        SearchMobileFragmentDirections.actionSearchToPeople(
                            id = people.id,
                            name = people.name,
                            image = people.image,
                        )
                    )
                }
            }
        }

        binding.ivPeopleImage.apply {
            clipToOutline = true
            Glide.with(context)
                .load(people.image)
                .placeholder(R.drawable.ic_person_placeholder)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.tvPeopleName.text = people.name
        binding.root.contentDescription = buildPeopleDescription()
        binding.tvPeopleCharacter.apply {
            val role = people.character?.takeIf { it.isNotBlank() }
            text = role
            visibility = if (role == null) View.GONE else View.VISIBLE
        }
    }

    private fun displayTvItem(binding: ItemPeopleTvBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                when (context.toActivity()?.getCurrentFragment()) {
                    is MovieTvFragment -> findNavController().navigate(
                        MovieTvFragmentDirections.actionMovieToPeople(
                            id = people.id,
                            name = people.name,
                            image = people.image,
                        )
                    )
                    is TvShowTvFragment -> findNavController().navigate(
                        TvShowTvFragmentDirections.actionTvShowToPeople(
                            id = people.id,
                            name = people.name,
                            image = people.image,
                        )
                    )
                    is SearchTvFragment -> findNavController().navigate(
                        SearchTvFragmentDirections.actionSearchToPeople(
                            id = people.id,
                            name = people.name,
                            image = people.image,
                        )
                    )
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(binding.ivPeopleImage, hasFocus)
            }
        }

        val mode = com.betterstreamflix.app.ui.LeanbackDensityPolicy.mode()
        val itemW = com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
            binding.root,
            com.betterstreamflix.app.ui.LeanbackDensityPolicy.peopleItemWidthDp(mode),
        )
        val avatar = com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
            binding.root,
            com.betterstreamflix.app.ui.LeanbackDensityPolicy.peopleAvatarSizeDp(mode),
        )
        binding.root.layoutParams = binding.root.layoutParams?.also { it.width = itemW }
            ?: ViewGroup.LayoutParams(itemW, ViewGroup.LayoutParams.WRAP_CONTENT)
        binding.ivPeopleImage.layoutParams = binding.ivPeopleImage.layoutParams?.also {
            it.width = avatar
            it.height = avatar
        }
        binding.ivPeopleImage.apply {
            clipToOutline = true
            Glide.with(context)
                .load(people.image)
                .placeholder(R.drawable.ic_person_placeholder)
                .override(avatar, avatar)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.tvPeopleName.text = people.name
        binding.root.contentDescription = buildPeopleDescription()
        binding.tvPeopleCharacter.apply {
            val role = people.character?.takeIf { it.isNotBlank() }
            text = role
            visibility = if (role == null) View.GONE else View.VISIBLE
        }
    }

    private fun buildPeopleDescription(): String {
        val role = people.character?.takeIf { it.isNotBlank() }
        return if (role != null) "${people.name}, $role" else people.name
    }
}