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

  def install
    # The tarball should contain the standalone 'midra' binary
    bin.install "midra"
  end

  test do
    # Simple test to verify the binary executes and outputs the help message
    system "#{bin}/midra", "--help"
  end
end
