class Midra < Formula
  desc "A blazing fast, cross-platform CLI MIDI player built with GraalVM"
  homepage "https://github.com/sungchulpark/midiraja"
  version "0.1.0"
  
  # TODO: These URLs and SHAs will be dynamically updated by GitHub Actions during a release.
  if OS.mac?
    if Hardware::CPU.arm?
      url "https://github.com/sungchulpark/midiraja/releases/download/v#{version}/midra-macos-aarch64.tar.gz"
      sha256 "REPLACE_ME_MACOS_ARM_SHA"
    else
      url "https://github.com/sungchulpark/midiraja/releases/download/v#{version}/midra-macos-x64.tar.gz"
      sha256 "REPLACE_ME_MACOS_X64_SHA"
    end
  elsif OS.linux?
    url "https://github.com/sungchulpark/midiraja/releases/download/v#{version}/midra-linux-x64.tar.gz"
    sha256 "REPLACE_ME_LINUX_X64_SHA"
  end

  resource "freepats" do
    url "https://github.com/feross/freepats/archive/refs/heads/master.zip"
    sha256 "9fcb812dc1ef7743854093af6db25fdd90b0373f751c53a9929146a771f7f48d"
  end

  def install
    # The tarball should contain the standalone 'midra' binary
    bin.install "midra"

    # Install Freepats to pkgshare (e.g., /opt/homebrew/share/midra/freepats)
    resource("freepats").stage do
      (pkgshare/"freepats").install Dir["*"]
      # Ensure timidity.cfg exists for our engine
      cp (pkgshare/"freepats/freepats.cfg"), (pkgshare/"freepats/timidity.cfg")
    end

    # Generate and install autocompletion scripts
    system "#{bin}/midra", "generate-completion"
    bash_completion.install "midra_completion" => "midra"
    
    # Optional: If you want to rename it for zsh specifically, you can do:
    # zsh_completion.install "midra_completion" => "_midra"
    # But Homebrew can usually handle the bash script for both if it's sourced properly,
    # or PicoCLI's script is compatible. Let's provide it for zsh too.
    zsh_completion.install "midra_completion" => "_midra"
  end

  test do
    # Simple test to verify the binary executes and outputs the help message
    system "#{bin}/midra", "--help"
  end
end
