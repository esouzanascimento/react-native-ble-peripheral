require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
    s.name         = "BLEPeripheral"
    s.version      = package["version"]
    s.summary      = "BLE Peripheral Library"
    s.description  = package["description"]
    s.author       = { "Emerson Nascimento" => "emerson@i2l.ca" }
    s.license      = { :type => "MIT" }
    s.homepage     = "https://github.com/esouzanascimento/react-native-ble-peripheral"
    s.source       = { :git => "https://github.com/esouzanascimento/react-native-ble-peripheral.git", :tag => "#{s.version}" }
    s.platform     = :ios, "10.0"
    s.source_files  = "ios/**/*.{h,m,swift}"
    s.dependency 'React'
  end
  