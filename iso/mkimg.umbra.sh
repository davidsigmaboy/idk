profile_umbra() {
	profile_standard
	title="Umbra"
	desc="Umbra: Telegram client that boots straight into a full-screen window."
	profile_abbrev="umbra"
	image_ext="iso"
	arch="aarch64"
	output_format="iso"
	kernel_flavors="lts"
	kernel_cmdline="unionfs_size=1G console=tty0 console=ttyAMA0,115200"
	apks="$apks chromium cage seatd mesa-dri-gallium mesa-egl mesa-gles mesa-gbm eudev dbus libinput
		font-dejavu font-noto-emoji python3 openssl ca-certificates agetty doas"
	local _k _a
	for _k in $kernel_flavors; do
		apks="$apks linux-$_k"
		for _a in $kernel_addons; do
			apks="$apks $_a-$_k"
		done
	done
	apkovl="genapkovl-umbra.sh"
	hostname="umbra"
}
