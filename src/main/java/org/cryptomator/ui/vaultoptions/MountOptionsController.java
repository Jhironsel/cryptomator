package org.cryptomator.ui.vaultoptions;

import com.google.common.base.Strings;
import org.cryptomator.common.mount.ActualMountService;
import org.cryptomator.common.mount.WindowsDriveLetters;
import org.cryptomator.common.settings.VaultSettings;
import org.cryptomator.common.vaults.Vault;
import org.cryptomator.integrations.mount.MountCapability;
import org.cryptomator.ui.common.FxController;
import org.cryptomator.ui.fxapp.FxApplicationWindows;
import org.cryptomator.ui.preferences.SelectedPreferencesTab;

import javax.inject.Inject;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ResourceBundle;
import java.util.Set;

@VaultOptionsScoped
public class MountOptionsController implements FxController {

	private final Stage window;
	private final VaultSettings vaultSettings;
	private final WindowsDriveLetters windowsDriveLetters;
	private final ResourceBundle resourceBundle;

	private final ObservableValue<String> defaultMountFlags;
	private final ObservableValue<Boolean> mountpointDirSupported;
	private final ObservableValue<Boolean> mountpointDriveLetterSupported;
	private final ObservableValue<Boolean> volumeIdSupported;
	private final ObservableValue<Boolean> readOnlySupported;
	private final ObservableValue<Boolean> mountFlagsSupported;
	private final ObservableValue<String> directoryPath;
	private final FxApplicationWindows applicationWindows;


	//-- FXML objects --
	public CheckBox readOnlyCheckbox;
	public CheckBox useVaultIdAsVolumeId;
	public CheckBox customMountFlagsCheckbox;
	public TextField mountFlagsField;
	public ToggleGroup mountPointToggleGroup;
	public RadioButton mountPointAutoBtn;
	public RadioButton mountPointDriveLetterBtn;
	public RadioButton mountPointDirBtn;
	public TextField directoryPathField;
	public ChoiceBox<Path> driveLetterSelection;

	@Inject
	MountOptionsController(@VaultOptionsWindow Stage window, @VaultOptionsWindow Vault vault, ObservableValue<ActualMountService> mountService, WindowsDriveLetters windowsDriveLetters, ResourceBundle resourceBundle, FxApplicationWindows applicationWindows) {
		this.window = window;
		this.vaultSettings = vault.getVaultSettings();
		this.windowsDriveLetters = windowsDriveLetters;
		this.resourceBundle = resourceBundle;
		this.defaultMountFlags = mountService.map(as -> {
			if (as.service().hasCapability(MountCapability.MOUNT_FLAGS)) {
				return as.service().getDefaultMountFlags();
			} else {
				return "";
			}
		});
		this.mountpointDirSupported = mountService.map(as -> as.service().hasCapability(MountCapability.MOUNT_TO_EXISTING_DIR) || as.service().hasCapability(MountCapability.MOUNT_WITHIN_EXISTING_PARENT));
		this.mountpointDriveLetterSupported = mountService.map(as -> as.service().hasCapability(MountCapability.MOUNT_AS_DRIVE_LETTER));
		this.mountFlagsSupported = mountService.map(as -> as.service().hasCapability(MountCapability.MOUNT_FLAGS));
		this.volumeIdSupported = mountService.map(as -> as.service().hasCapability(MountCapability.VOLUME_ID));
		this.readOnlySupported = mountService.map(as -> as.service().hasCapability(MountCapability.READ_ONLY));
		this.directoryPath = vault.getVaultSettings().mountPoint().map(p -> isDriveLetter(p) ? null : p.toString());
		this.applicationWindows = applicationWindows;
	}

	@FXML
	public void initialize() {
		// readonly:
		readOnlyCheckbox.selectedProperty().bindBidirectional(vaultSettings.usesReadOnlyMode());

		// use volume name as volume id
		useVaultIdAsVolumeId.selectedProperty().bindBidirectional(vaultSettings.usesVaultIdAsVolumeId());

		// custom mount flags:
		mountFlagsField.disableProperty().bind(customMountFlagsCheckbox.selectedProperty().not());
		customMountFlagsCheckbox.setSelected(!Strings.isNullOrEmpty(vaultSettings.mountFlags().getValue()));
		toggleUseCustomMountFlags();

		//driveLetter choice box
		driveLetterSelection.getItems().addAll(windowsDriveLetters.getAll());
		driveLetterSelection.setConverter(new WinDriveLetterLabelConverter(windowsDriveLetters, resourceBundle));

		//mountPoint toggle group
		var mountPoint = vaultSettings.getMountPoint();
		if (mountPoint == null) {
			//prepare and select auto
			mountPointToggleGroup.selectToggle(mountPointAutoBtn);
		} else if (mountPoint.getParent() == null && isDriveLetter(mountPoint)) {
			//prepare and select drive letter
			mountPointToggleGroup.selectToggle(mountPointDriveLetterBtn);
			driveLetterSelection.valueProperty().bindBidirectional(vaultSettings.mountPoint());
		} else {
			//prepare and select dir
			mountPointToggleGroup.selectToggle(mountPointDirBtn);
		}
		mountPointToggleGroup.selectedToggleProperty().addListener(this::selectedToggleChanged);
	}

	@FXML
	public void openVolumePreferences() {
		applicationWindows.showPreferencesWindow(SelectedPreferencesTab.VOLUME);
	}

	@FXML
	public void toggleUseCustomMountFlags() {
		if (customMountFlagsCheckbox.isSelected()) {
			readOnlyCheckbox.setSelected(false); // to prevent invalid states
			mountFlagsField.textProperty().unbind();
			var mountFlags = vaultSettings.mountFlags().get();
			if (mountFlags == null || mountFlags.isBlank()) {
				vaultSettings.mountFlags().set(defaultMountFlags.getValue());
			}
			mountFlagsField.textProperty().bindBidirectional(vaultSettings.mountFlags());
		} else {
			mountFlagsField.textProperty().unbindBidirectional(vaultSettings.mountFlags());
			vaultSettings.mountFlags().set(null);
			mountFlagsField.textProperty().bind(defaultMountFlags);
		}
	}

	@FXML
	public void chooseCustomMountPoint() {
		try {
			Path chosenPath = chooseCustomMountPointInternal();
			vaultSettings.mountPoint().set(chosenPath);
		} catch (NoDirSelectedException e) {
			//no-op
		}
	}

	/**
	 * Prepares and opens a directory chooser dialog.
	 * This method blocks until the dialog is closed.
	 *
	 * @return the absolute path to the chosen directory
	 * @throws NoDirSelectedException if dialog is closed without choosing a directory
	 */
	private Path chooseCustomMountPointInternal() throws NoDirSelectedException {
		DirectoryChooser directoryChooser = new DirectoryChooser();
		directoryChooser.setTitle(resourceBundle.getString("vaultOptions.mount.mountPoint.directoryPickerTitle"));
		try {
			var mp = vaultSettings.mountPoint().get();
			var initialDir = mp != null && !isDriveLetter(mp) ? mp : Path.of(System.getProperty("user.home"));

			if (Files.isDirectory(initialDir)) {
				directoryChooser.setInitialDirectory(initialDir.toFile());
			}
		} catch (InvalidPathException e) {
			// no-op
		}
		File file = directoryChooser.showDialog(window);
		if (file != null) {
			return file.toPath();
		} else {
			throw new NoDirSelectedException();
		}
	}

	private void selectedToggleChanged(ObservableValue<? extends Toggle> observable, Toggle oldToggle, Toggle newToggle) {
		//Remark: the mountpoint corresponding to the newToggle must be null, otherwise it would not be new!
		driveLetterSelection.valueProperty().unbindBidirectional(vaultSettings.mountPoint());
		if (mountPointDriveLetterBtn.equals(newToggle)) {
			vaultSettings.mountPoint().set(windowsDriveLetters.getFirstDesiredAvailable().orElse(windowsDriveLetters.getAll().stream().findAny().get()));
			driveLetterSelection.valueProperty().bindBidirectional(vaultSettings.mountPoint());
		} else if (mountPointDirBtn.equals(newToggle)) {
			try {
				vaultSettings.mountPoint().set(chooseCustomMountPointInternal());
			} catch (NoDirSelectedException e) {
				if (oldToggle != null && !mountPointDirBtn.equals(oldToggle)) {
					mountPointToggleGroup.selectToggle(oldToggle);
				} else {
					mountPointToggleGroup.selectToggle(mountPointAutoBtn);
				}
			}
		} else {
			vaultSettings.mountPoint().set(null);
		}
	}

	private boolean isDriveLetter(Path mountPoint) {
		if (mountPoint != null) {
			var s = mountPoint.toString();
			return s.length() == 3 && s.endsWith(":\\");
		}
		return false;
	}

	private static class WinDriveLetterLabelConverter extends StringConverter<Path> {

		private final Set<Path> occupiedDriveLetters;
		private final ResourceBundle resourceBundle;

		WinDriveLetterLabelConverter(WindowsDriveLetters windowsDriveLetters, ResourceBundle resourceBundle) {
			this.occupiedDriveLetters = windowsDriveLetters.getOccupied();
			this.resourceBundle = resourceBundle;
		}

		@Override
		public String toString(Path driveLetter) {
			if (driveLetter == null) {
				return "";
			} else if (occupiedDriveLetters.contains(driveLetter)) {
				return driveLetter.toString().substring(0, 2) + " (" + resourceBundle.getString("vaultOptions.mount.winDriveLetterOccupied") + ")";
			} else {
				return driveLetter.toString().substring(0, 2);
			}
		}

		@Override
		public Path fromString(String string) {
			if (string.isEmpty()) {
				return null;
			} else {
				return Path.of(string + "\\");
			}
		}

	}

	//@formatter:off
	private static class NoDirSelectedException extends Exception {}
	//@formatter:on

	// Getter & Setter

	public ObservableValue<Boolean> mountFlagsSupportedProperty() {
		return mountFlagsSupported;
	}

	public boolean isMountFlagsSupported() {
		return mountFlagsSupported.getValue();
	}

	public ObservableValue<Boolean> mountpointDirSupportedProperty() {
		return mountpointDirSupported;
	}

	public boolean isMountpointDirSupported() {
		return mountpointDirSupported.getValue();
	}

	public ObservableValue<Boolean> mountpointDriveLetterSupportedProperty() {
		return mountpointDriveLetterSupported;
	}

	public boolean isMountpointDriveLetterSupported() {
		return mountpointDriveLetterSupported.getValue();
	}

	public ObservableValue<Boolean> readOnlySupportedProperty() {
		return readOnlySupported;
	}

	public boolean isReadOnlySupported() {
		return readOnlySupported.getValue();
	}

	public ObservableValue<Boolean> volumeIdSupportedProperty() {
		return volumeIdSupported;
	}

	public boolean getVolumeIdSupported() {
		return volumeIdSupported.getValue();
	}



	public ObservableValue<String> directoryPathProperty() {
		return directoryPath;
	}

	public String getDirectoryPath() {
		return directoryPath.getValue();
	}
}
