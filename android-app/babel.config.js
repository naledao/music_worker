module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    'react-native-css-interop/dist/babel-plugin',
    [
      '@babel/plugin-transform-react-jsx',
      {
        runtime: 'automatic',
        importSource: 'nativewind',
      },
    ],
  ],
};
