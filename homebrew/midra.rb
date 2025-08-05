class Midra < Formula
  desc "High-performance MIDI player for CLI."
  homepage "https://github.com/YOUR_GITHUB_USERNAME/playmidi"
  version "1.1.0"
  
  if OS.mac?
    if Hardware::CPU.arm?
      url "https://github.com/YOUR_GITHUB_USERNAME/playmidi/releases/download/v#{version}/midra-darwin-arm64-v#{version}.tar.gz"
      sha256 "a79ab83831e7b1185ab1ebe78840bf9f7d814fe1170011091742db93dd9df870" # Update this from dist/checksum!
    elsif Hardware::CPU.intel?
      url "https://github.com/YOUR_GITHUB_USERNAME/playmidi/releases/download/v#{version}/midra-darwin-amd64-v#{version}.tar.gz"
      sha256 "REPLACE_WITH_INTEL_SHA" # Update this!
    end
  elsif OS.linux?
    if Hardware::CPU.arm?
      url "https://github.com/YOUR_GITHUB_USERNAME/playmidi/releases/download/v#{version}/midra-linux-arm64-v#{version}.tar.gz"
      sha256 "REPLACE_WITH_LINUX_ARM_SHA" # Update this!
    elsif Hardware::CPU.intel?
      url "https://github.com/YOUR_GITHUB_USERNAME/playmidi/releases/download/v#{version}/midra-linux-amd64-v#{version}.tar.gz"
      sha256 "REPLACE_WITH_LINUX_INTEL_SHA" # Update this!
    end
  end

  def install
    bin.install "midra"
  end

  test do
    system "#{bin}/midra", "--version"
  end
end
