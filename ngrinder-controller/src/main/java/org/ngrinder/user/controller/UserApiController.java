package org.ngrinder.user.controller;

import static java.util.stream.Collectors.toList;
import static org.ngrinder.common.constant.ControllerConstants.NGRINDER_INITIAL_ADMIN_USERID;
import static org.ngrinder.common.util.CollectionUtils.buildMap;
import static org.ngrinder.common.util.ObjectUtils.defaultIfNull;
import static org.ngrinder.common.util.Preconditions.*;

import org.apache.commons.lang.StringUtils;
import org.ngrinder.common.controller.BaseController;
import org.ngrinder.common.controller.RestAPI;
import org.ngrinder.infra.config.Config;
import org.ngrinder.model.Permission;
import org.ngrinder.model.Role;
import org.ngrinder.model.User;
import org.ngrinder.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user/api")
public class UserApiController extends BaseController {
	public static final Sort DEFAULT_SORT = new Sort(Sort.Direction.ASC, "userName");

	@Autowired
	protected Config config;

	@Autowired
	private UserService userService;

	/**
	 * Get the follower list.
	 *
	 * @param user     current user
	 * @param keywords search keyword.
	 * @return json message
	 */
	@RestAPI
	@GetMapping("/switch_options")
	public List<User> switchOptions(User user, @RequestParam(defaultValue = "") final String keywords) {
		if (user.getRole().hasPermission(Permission.SWITCH_TO_ANYONE)) {
			return userService.getPagedAll(keywords, PageRequest.of(0, 10)).getContent();
		} else {
			return userService.getSharedUser(user);
		}
	}

	/**
	 * Get the current user profile.
	 *
	 * @param user  current user
	 */
	@GetMapping("/profile")
	public Map<String, Object> getOne(User user) {
		checkNotEmpty(user.getUserId(), "UserID should not be NULL!");
		Map<String, Object> model = new HashMap<>();
		User one = userService.getOneWithFollowers(user.getUserId());
		model.put("user", one);
		model.put("allowPasswordChange", !config.isDemo());
		model.put("allowRoleChange", false);
		model.put("showPasswordByDefault", false);
		attachCommonAttribute(one, model);
		return model;
	}

	/**
	 * Get user creation form page.
	 *
	 * @param user current user
	 * @return app
	 */
	@RequestMapping("/new")
	@PreAuthorize("hasAnyRole('A') or #user.userId == #userId")
	public Map<String, Object> openForm(User user) {
		User one = User.createNew();

		Map<String, Object> model = new HashMap<>(7);
		model.put("user", one);
		model.put("allowUserIdChange", true);
		model.put("allowPasswordChange", true);
		model.put("allowRoleChange", false);
		model.put("roleSet", EnumSet.allOf(Role.class));
		model.put("showPasswordByDefault", true);

		attachCommonAttribute(one, model);
		return model;
	}

	/**
	 * Get user detail.
	 *
	 * @param userId user to get
	 * @return user details
	 */
	@GetMapping("/{userId}/detail")
	@PreAuthorize("hasAnyRole('A')")
	public Map<String, Object> getOneDetail(@PathVariable final String userId) {
		User one = userService.getOneWithFollowers(userId);
		Map<String, Object> model = buildMap(
			"user", one,
			"allowPasswordChange", true,
			"allowRoleChange", true,
			"roleSet", EnumSet.allOf(Role.class),
			"showPasswordByDefault", false
		);
		attachCommonAttribute(one, model);
		return model;
	}

	@PreAuthorize("hasAnyRole('A')")
	@RequestMapping({"/list", "/list/"})
	public Page<User> getAll(@RequestParam(required = false) Role role,
							 @PageableDefault(page = 0, size = 10) Pageable pageable,
							 @RequestParam(required = false) String keywords) {
		pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultIfNull(pageable.getSort(), DEFAULT_SORT));
		Pageable defaultPageable = PageRequest.of(0, pageable.getPageSize(), defaultIfNull(pageable.getSort(), DEFAULT_SORT));
		Page<User> pagedUser;
		if (StringUtils.isEmpty(keywords)) {
			pagedUser = userService.getPagedAll(role, pageable);
			if (pagedUser.getNumberOfElements() == 0) {
				pagedUser = userService.getPagedAll(role, defaultPageable);
			}
		} else {
			pagedUser = userService.getPagedAll(keywords, pageable);
			if (pagedUser.getNumberOfElements() == 0) {
				pagedUser = userService.getPagedAll(keywords, defaultPageable);
			}
		}

		return pagedUser;
	}

	/**
	 * Get user list that current user will be shared, excluding current user.
	 *
	 * @param user  current user
	 * @param model model
	 */
	private void attachCommonAttribute(User user, Map<String, Object> model) {
		model.put("allowShareChange", true);
		model.put("userSecurityEnabled", config.isUserSecurityEnabled());
	}

	/**
	 * Save or Update user detail info.
	 *
	 * @param user        current user
	 * @param updatedUser user to be updated.
	 * @return "redirect:/user/list" if current user change his info, otherwise return "redirect:/"
	 */
	@RequestMapping("/save")
	@PreAuthorize("hasAnyRole('A') or #user.id == #updatedUser.id")
	public String save(User user, @RequestBody User updatedUser) {
		checkArgument(updatedUser.validate());
		if (user.getRole() == Role.USER) {
			// General user can not change their role.
			User updatedUserInDb = userService.getOne(updatedUser.getUserId());
			checkNotNull(updatedUserInDb);
			updatedUser.setRole(updatedUserInDb.getRole());

			// prevent user to modify with other user id
			checkArgument(updatedUserInDb.getId().equals(updatedUser.getId()), "Illegal request to update user:%s",
				updatedUser);
		}
		save(updatedUser);
		return returnSuccess();
	}

	private User save(User user) {
		if (StringUtils.isBlank(user.getPassword())) {
			return userService.saveWithoutPasswordEncoding(user);
		} else {
			return userService.save(user);
		}
	}


	@PreAuthorize("hasAnyRole('A')")
	@GetMapping({"/role", "/role/"})
	public EnumSet<Role> roleSet() {
		return EnumSet.allOf(Role.class);
	}

	/**
	 * Delete users.
	 *
	 * @param userIds comma separated user ids.
	 * @return "redirect:/user/"
	 */
	@PreAuthorize("hasAnyRole('A')")
	@DeleteMapping({"", "/"})
	public HttpEntity<String> deleteUsers(User user, @RequestParam String userIds) {
		String[] ids = userIds.split(",");
		for (String eachId : ids) {
			if (!user.getUserId().equals(eachId)) {
				userService.delete(eachId);
			}
		}
		return successJsonHttpEntity();
	}

	/**
	 * Check if the given user id already exists.
	 *
	 * @param userId userId to be checked
	 * @return success json if true.
	 */
	@RestAPI
	@PreAuthorize("hasAnyRole('A')")
	@RequestMapping("/{userId}/check_duplication")
	public HttpEntity<String> checkDuplication(@PathVariable String userId) {
		User user = userService.getOne(userId);
		return (user == null) ? successJsonHttpEntity() : errorJsonHttpEntity();
	}

	/**
	 * Get users by the given role.
	 *
	 * @param role user role
	 * @return json message
	 */
	@RestAPI
	@PreAuthorize("hasAnyRole('A')")
	@RequestMapping(value = {"/", ""}, method = RequestMethod.GET)
	public HttpEntity<String> getAll(Role role) {
		return toJsonHttpEntity(userService.getAll(role));
	}

	/**
	 * Get the user by the given user id.
	 *
	 * @param userId user id
	 * @return json message
	 */
	@RestAPI
	@PreAuthorize("hasAnyRole('A')")
	@RequestMapping(value = "/{userId}", method = RequestMethod.GET)
	public User getOne(@PathVariable("userId") String userId) {
		return userService.getOne(userId);
	}

	/**
	 * Create an user.
	 *
	 * @param newUser new user
	 * @return json message
	 */
	@RestAPI
	@PreAuthorize("hasAnyRole('A')")
	@RequestMapping(value = {"/", ""}, method = RequestMethod.POST)
	public HttpEntity<String> create(@ModelAttribute("user") User newUser) {
		checkNull(newUser.getId(), "User DB ID should be null");
		return toJsonHttpEntity(save(newUser));
	}

	/**
	 * Update the user.
	 *
	 * @param userId user id
	 * @param update update user
	 * @return json message
	 */
	@RestAPI
	@PreAuthorize("hasAnyRole('A')")
	@RequestMapping(value = "/{userId}", method = RequestMethod.PUT)
	public HttpEntity<String> update(@PathVariable("userId") String userId, User update) {
		update.setUserId(userId);
		checkNull(update.getId(), "User DB ID should be null");
		return toJsonHttpEntity(save(update));
	}

	/**
	 * Delete the user by the given userId.
	 *
	 * @param userId user id
	 * @return json message
	 */
	@RestAPI
	@PreAuthorize("hasAnyRole('A')")
	@RequestMapping(value = "/{userId}", method = RequestMethod.DELETE)
	public HttpEntity<String> delete(User user, @PathVariable("userId") String userId) {
		if (!user.getUserId().equals(userId)) {
			userService.delete(userId);
		}
		return successJsonHttpEntity();
	}

	/**
	 * Search user list on the given keyword.
	 *
	 * @param pageable page info
	 * @param keywords search keyword.
	 * @return json message
	 */
	@RestAPI
	@RequestMapping(value = "/search", method = RequestMethod.GET)
	public List<User> search(User user,
							 @PageableDefault Pageable pageable,
							 @RequestParam String keywords) {
		pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultIfNull(pageable.getSort(), DEFAULT_SORT));
		Page<User> pagedUsers = userService.getPagedAll(keywords, pageable);
		final String currentUserId = user.getUserId();

		return pagedUsers.stream()
			.filter(each -> !each.getUserId().equals(currentUserId))
			.filter(each -> !each.getUserId().equals(NGRINDER_INITIAL_ADMIN_USERID))
			.collect(toList());
	}
}