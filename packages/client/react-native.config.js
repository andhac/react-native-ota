export default {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.rnota.OtaPackage;',
        packageInstance: 'new OtaPackage()',
      },
      ios: {
        podspecPath: './react-native-ota.podspec',
      },
    },
  },
};
