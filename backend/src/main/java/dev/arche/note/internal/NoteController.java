package dev.arche.note.internal;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 쪽지 (R74). 로그인한 직원이면 누구나 쓴다(권한 없음). 자기 쪽지만 보인다 */
@RestController
@RequestMapping("/api/notes")
class NoteController {

	private final NoteService service;

	NoteController(NoteService service) {
		this.service = service;
	}

	@GetMapping("/inbox")
	List<NoteService.InboxItem> inbox(@RequestParam(defaultValue = "false") boolean unreadOnly) {
		return service.inbox(unreadOnly);
	}

	@GetMapping("/sent")
	List<NoteService.SentItem> sent() {
		return service.sent();
	}

	@GetMapping("/unread-count")
	Map<String, Integer> unreadCount() {
		return Map.of("count", service.unreadCount());
	}

	@GetMapping("/directory")
	NoteService.Directory directory() {
		return service.directory();
	}

	@PostMapping
	Map<String, Long> send(@Valid @RequestBody NoteService.SendCommand c) {
		return Map.of("id", service.send(c));
	}

	/** 열기 = 읽음 표시라서 GET이 아니라 POST */
	@PostMapping("/{id}/open")
	NoteService.Detail open(@PathVariable long id) {
		return service.open(id);
	}

	@PostMapping("/{id}/hide")
	void hide(@PathVariable long id) {
		service.hide(id);
	}
}
