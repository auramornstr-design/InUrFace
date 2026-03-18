# In Your Face — Adaptive Interface Overlay System
# Authors: Sunni (Sir) Morningstar and Cael Devo
#
# Gemfile — Ruby gem dependencies for Fastlane
#
# Usage:
#   bundle install          — install all gems
#   bundle exec fastlane    — run fastlane through bundler (always use this form)
#
# After cloning: run `bundle install` before any fastlane commands.

source "https://rubygems.org"

# Fastlane core
gem "fastlane", "~> 2.220"

# Fastlane plugins used in our lanes
plugins_path = File.join(File.dirname(__FILE__), 'fastlane', 'Pluginfile')
eval_gemfile(plugins_path) if File.exist?(plugins_path)
