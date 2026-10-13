# Grumanda-server-mod

## Command

Players can save their current block coordinates as a location with `/post <name>`, for example `/post Base`. Names may contain spaces. The entry stores the player's name and UUID, coordinates, and dimension in `grumanda-server.db`.

All players can get light blocks with `/lichtblock [anzahl]`. The amount defaults to 1 and can be set from 1 to 64.

Players can create a one-use image placement item with `/bild <direct image URL> <width> <height>`, for example `/bild https://example.org/image.gif 5 3`. Right-click the lower-left block face of a vertical, solid wall with the item; the image is placed upward and to the right using vanilla map tiles in item frames. Width and height are each limited to 50 blocks, with a maximum of 1,500 map tiles per image. Animated GIFs are supported up to 25 map tiles and 20 frames. No client mod or resource pack is required.

Use `/bildgroesse <direct image URL> <breite|hoehe> <blocks>` to calculate the other dimension from the image's aspect ratio. The command prints the matching `/bild` command. For example: `/bildgroesse https://example.org/image.png breite 30`.

## Auto-updater

The server checks the latest published release in [GitHub Releases](https://github.com/Grumanda/grumanda-server-mod/releases) at startup and every six hours. Operators can also run `/grumanda check` to check immediately. Operators are notified in chat and can click the update button (or run `/grumanda update`). The updater downloads and validates the matching mod JAR, installs it under its release-version filename, removes older JARs for this mod, verifies the installed JAR version, and runs `/usr/bin/sudo -n /usr/bin/systemctl restart minecraft`.

The service account needs write permission on `/opt/minecraft/server/mods` to replace a JAR. Check which account runs the service with `sudo systemctl show minecraft -p User -p WorkingDirectory`. If it runs as `minecraft`, grant that account ownership of the mods directory (this does not change the JARs or other server files):

```sh
sudo chown minecraft:minecraft /opt/minecraft/server/mods
sudo chmod u+rwx /opt/minecraft/server/mods
```

The service account must also be allowed to restart the systemd unit without a password. Create a narrow sudoers rule with `sudo visudo -f /etc/sudoers.d/minecraft-mod-updater` and add `minecraft ALL=(root) NOPASSWD: /usr/bin/systemctl restart minecraft`. Check the installed rule without restarting the server using `sudo -l -U minecraft /usr/bin/systemctl restart minecraft`. If `systemctl` is installed at a different path, update both the sudoers rule and the updater command to match.

To publish an update, push a version tag such as `v1.0.1`. The release workflow builds the matching version and publishes the JAR as `grumanda-server-mod-1.0.1.jar`. Add the change summary to the GitHub release notes; operators, including those who were offline during the update, receive that changelog when they next join after the restart.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
