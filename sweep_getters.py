import glob, re

MAPPING = {
    'glassCardBackground': 'R.drawable.bg_cinema_glass_card',
    'bottomSheetBackground': 'R.drawable.bg_profile_dialog',
    'castMiniBackground': 'R.drawable.bg_cinema_glass_card',
    'navPillBackground': 'R.drawable.bg_meta_pill',
    'dialogBackground': 'R.drawable.bg_profile_dialog',
    'metaPillBackground': 'R.drawable.bg_meta_pill',
    'searchFieldBackground': 'R.drawable.bg_search_edittext',
    'chipBackground': 'R.drawable.bg_outlined_chip',
    'optionItemBackground': 'R.drawable.bg_outlined_chip',
    'iconChipBackground': 'R.drawable.bg_detail_icon_chip',
    'controlsPillBackground': 'R.drawable.bg_meta_pill',
    'liveIndicatorBackground': 'R.drawable.bg_meta_pill',
    'spinnerBackground': 'R.drawable.bg_profile_dialog',
    'primaryButtonBackground': 'R.drawable.bg_button_mobile',
    'accentCssHex': '"#5B8CFF"',
}

total = 0
for f in glob.glob('app/src/main/java/**/*.kt', recursive=True):
    if f.replace('\\', '/').endswith('utils/ExperimentalMobileDesign.kt'):
        continue
    src = open(f, encoding='utf-8').read()
    orig = src
    for name, res in MAPPING.items():
        # FQCN form
        src = src.replace(
            f'com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.{name}()',
            res)
        src = src.replace(f'ExperimentalMobileDesign.{name}()', res)
    if src != orig:
        open(f, 'w', encoding='utf-8').write(src)
        total += 1
        print(f)
print('TOTAL', total)
