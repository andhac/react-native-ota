require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

# Empty native shell for MS0. TurboModules / Resolver / Store land in later milestones.
# New Architecture: set RCT_NEW_ARCH_ENABLED=1 in the host app (default on RN 0.82+).
Pod::Spec.new do |s|
  s.name         = "react-native-ota"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://github.com/react-native-ota/react-native-ota"
  s.license      = package["license"]
  s.authors      = { "react-native-ota" => "maintainers@example.com" }

  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => "https://github.com/react-native-ota/react-native-ota.git", :tag => "v#{s.version}" }

  s.source_files = "ios/RNOta/**/*.{h,m,mm,swift}"
  s.public_header_files = "ios/RNOta/**/*.h"

  s.dependency "React-Core"

  # New Architecture notes (documented for integrators; no codegen yet in MS0):
  # - Host apps on RN 0.82+ enable bridgeless / Fabric by default.
  # - MS2+ will add RCT_NEW_ARCH_ENABLED-gated TurboModule sources here.
end
