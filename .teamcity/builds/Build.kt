package builds

import jetbrains.buildServer.configs.kotlin.Project
import jetbrains.buildServer.configs.kotlin.sequential
import jetbrains.buildServer.configs.kotlin.toId
import jetbrains.buildServer.configs.kotlin.triggers.vcs

class Build(
  name: String,
  branchFilter: String,
  forPullRequests: Boolean,
  triggerRules: String? = null
) :
  Project(
      {
        this.id(name.toId())
        this.name = name

        val complete = Empty("${name}-complete", "complete")

        val bts = sequential {
          if (forPullRequests)
            buildType(WhiteListCheck("${name}-whitelist-check", "white-list check"))
          if (forPullRequests) dependentBuildType(PRCheck("${name}-pr-check", "pr check"))
          dependentBuildType(
              Maven(
                  "${name}-build",
                  "build",
                  "test-compile",
                  "",
              ),
          )
          dependentBuildType(
              Maven(
                  "${name}-test",
                  "test",
                  "verify",
                  size = LinuxSize.LARGE,
              ),
          )
          dependentBuildType(complete)
          if (!forPullRequests)
            collectArtifacts(dependentBuildType(Release("${name}-release", "release")))
        }

        bts.buildTypes().forEach {
          it.thisVcs()

          it.features {
            enableCommitStatusPublisher()
            if (forPullRequests) enablePullRequests()
          }

          buildType(it)
        }

        complete.triggers {
          vcs {
            this.branchFilter = branchFilter
            this.triggerRules = triggerRules
          }
        }
      },
  )
