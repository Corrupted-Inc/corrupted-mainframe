# Corrupted Mainframe
*A JDA discord bot written in the Kotlin programming language*

This is a general-purpose discord bot. Currently, it has moderation features, 
music, and various utilities.

## Building
1. Clone the repo<br>
 `git clone https://github.com/Corrupted-Inc/corrupted-mainframe.git`
1. CD into the directory
1. `./gradlew bot:jar` (linux/mac) or `gradlew.bat bot:jar` (windows)
   `bot:assemble` will make a sources and javadoc jar
1. The output jar will be located at bot/build/libs/corrupted-mainframe.jar

## Configuration
In the `image` directory, make a file called `config.json`.
This configuration file will follow this format:
```json
{
  "token": "bot token here",
  "permaAdmins": ["your user ID here", "optional other user id here"],
  "gitUrl": "https://github.com/Corrupted-Inc/corrupted-mainframe/",
  "blueAllianceToken": "TBA token"
}
```
[The Blue Alliance](https://www.thebluealliance.com/)'s api is used for the /zebra and /tba commands.  You can get a 
token from their website or leave the field blank if you don't need those commands.

## Running
1. Install [minikube](https://minikube.sigs.k8s.io/docs/) and start a cluster
2. Make sure you're in the project directory
3. `bash image/build.sh` (no windows support yet)
4. ```bash
   kubectl apply -f kubernetes/postgres-persistentvolumeclaim.yaml
   kubectl apply -f kubernetes/database-deployment.yaml
   kubectl apply -f kubernetes/database-service.yaml
   kubectl apply -f kubernetes/bot-deployment.yaml
   ```

## Contributing
Pull requests are welcome!  If you find a security vulnerability, please contact an admin
directly.  We can be found in [our discord](https://discord.gg/WF8HU47PDc).

## TODOs
 * New features
   * Update readme
   * Add botBuild to all DB tables
   * New calculator backend (ideally one that supports CAS or at least numeric equation solving)
   * Add common parent table for snowflakes?
   * General-purpose migrations system
   * Example plugin repository
   * Per-guild custom fight attacks
   * Change `table()` to generate SVGs and render them for actually good formatting
   * Moderation command log
   * Manager
     * Shard statistics
     * Restart shards
     * Deploy update
     * Profiling
     * Integrated with kubernetes
     * Centralized logging
     * Automated DB snapshots
   * General-purpose settings system
 * Finish
   * Quotes
   * Repost detector
   * Fight records
   * Sharding
 * Cleanup and refactoring
   * Remove unneeded tables and columns
   * Split up commands into yet more files
